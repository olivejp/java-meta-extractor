package fr.cafat.meta.extract.calls;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.Secrets;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.rest.EndpointExtractor;
import fr.cafat.meta.extract.rest.SpringMappings;
import fr.cafat.meta.model.Call;
import fr.cafat.meta.model.Source;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.Callers;
import fr.cafat.meta.spoon.PartialString;
import fr.cafat.meta.spoon.Provenance;
import fr.cafat.meta.spoon.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Appels HTTP sortants : interfaces {@code @FeignClient}, {@code RestTemplate}, {@code WebClient},
 * {@code RestClient} et client JAX-RS.
 *
 * <p>L'URL brute est celle du code (placeholders {@code ${...}} gardés, valeurs inconnues en
 * {@code {nom}}) ; elle est résolue avec la configuration du profil. Application cible déduite de
 * l'hôte (nom court, ou premier label d'un hôte {@code .svc[.cluster.local]}) ; Feign sans
 * {@code url} : nom du client (découverte de services).
 */
public final class CallExtractor {

  public static final String FEIGN = "feign";
  public static final String REST_TEMPLATE = "rest_template";
  public static final String WEB_CLIENT = "web_client";
  public static final String REST_CLIENT = "rest_client";
  public static final String JAXRS_CLIENT = "jaxrs_client";

  private static final Set<String> FEIGN_PACKAGES = Set.of("org.springframework.cloud.openfeign",
      "org.springframework.cloud.netflix.feign");
  private static final Set<String> REST_TEMPLATES = Set.of("RestTemplate", "RestOperations",
      "OAuth2RestTemplate", "OAuth2RestOperations");
  private static final Map<String, String> REST_TEMPLATE_VERBS = Map.ofEntries(Map.entry("getForObject", "GET"),
      Map.entry("getForEntity", "GET"), Map.entry("postForObject", "POST"), Map.entry("postForEntity", "POST"),
      Map.entry("postForLocation", "POST"), Map.entry("put", "PUT"), Map.entry("patchForObject", "PATCH"),
      Map.entry("delete", "DELETE"), Map.entry("headForHeaders", "HEAD"), Map.entry("optionsForAllow", "OPTIONS"));
  private static final Set<String> REST_TEMPLATE_GENERIC = Set.of("exchange", "execute");
  private static final Set<String> FLUENT_VERBS = Set.of("get", "post", "put", "delete", "patch", "head", "options",
      "method");
  private static final Pattern REQUEST_LINE = Pattern.compile("^\\s*([A-Z]+)\\s+(\\S*)");
  private static final Pattern SVC_HOST = Pattern.compile("^([a-z0-9][a-z0-9-]*)\\.(?:[a-z0-9-]+\\.)?svc(?:\\.cluster\\.local)?$");
  private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(?:(\\d+)\\$)?[sd]");
  private static final int MAX_DEPTH = 8;

  private final ExtractionContext ctx;
  private final List<Call> out = new ArrayList<>();

  /**
   * Prépare l'extraction des appels sortants.
   *
   * @param ctx contexte d'extraction de l'application (modèle, configuration, diagnostics)
   */
  public CallExtractor(ExtractionContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Appels HTTP sortants de tous les types du dépôt.
   *
   * @return appels dans l'ordre de parcours des types, ids non suffixés ; liste vide si aucun
   */
  public List<Call> extract() {
    for (CtType<?> t : ctx.types().all()) {
      CtAnnotation<?> feign = Annotations.find(t, FEIGN_PACKAGES, "FeignClient");
      if (feign != null) {
        feign(t, feign);
      }
    }
    for (CtType<?> t : ctx.types().all()) {
      if (t.getDeclaringType() != null) {
        continue;
      }
      List<CtInvocation<?>> invocations = new ArrayList<>(t.getElements(new TypeFilter<>(CtInvocation.class)));
      invocations.sort(Comparator.comparingInt(Provenance::offset));
      for (CtInvocation<?> inv : invocations) {
        invocation(inv);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- Feign

  private void feign(CtType<?> t, CtAnnotation<?> feign) {
    String name = resolveName(firstConstant(feign, "name", "value", "serviceId"));
    PartialString url = eval(Annotations.value(feign, "url"));
    PartialString basePath = eval(Annotations.value(feign, "path"));
    PartialString prefix = eval(SpringMappings.pathExpression(SpringMappings.find(t)));
    List<CtMethod<?>> methods = new ArrayList<>();
    for (CtTypeMember m : t.getTypeMembers()) {
      if (m instanceof CtMethod<?> method) {
        methods.add(method);
      }
    }
    methods.sort(Comparator.comparingInt(Provenance::offset));
    for (CtMethod<?> m : methods) {
      List<String> verbs;
      PartialString methodPath;
      CtAnnotation<?> mapping = SpringMappings.find(m);
      CtAnnotation<?> requestLine = Annotations.find(m, Set.of("feign"), "RequestLine");
      if (mapping != null) {
        // Feign (SpringMvcContract) prend GET quand @RequestMapping ne précise pas de méthode.
        verbs = SpringMappings.verbs(mapping);
        verbs = verbs.isEmpty() ? List.of("GET") : verbs;
        methodPath = eval(SpringMappings.pathExpression(mapping));
      } else if (requestLine != null) {
        Matcher rl = REQUEST_LINE.matcher(String.valueOf(ctx.str(requestLine, "value")));
        if (!rl.find()) {
          continue;
        }
        verbs = List.of(rl.group(1));
        methodPath = PartialString.lit(rl.group(2));
      } else {
        continue;
      }
      String path = EndpointExtractor.path(text(basePath), text(prefix), text(methodPath));
      String caller = t.getQualifiedName() + "#" + m.getSimpleName();
      for (String verb : verbs) {
        if (!url.isEmpty()) {
          resolveAndAdd(FEIGN, verb, join(text(url), path), caller, m);
        } else if (name != null) {
          add(FEIGN, verb, path, "http://" + name + path, path, name, caller, m);
        } else {
          ctx.diagnostics().warning("URL_UNRESOLVED", "client Feign sans url ni nom résolu : "
              + t.getQualifiedName(), ctx.source(m));
          add(FEIGN, verb, path, null, path, null, caller, m);
        }
      }
    }
  }

  private String firstConstant(CtAnnotation<?> a, String... keys) {
    for (String k : keys) {
      String v = ctx.str(a, k);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  private String resolveName(String name) {
    if (name == null) {
      return null;
    }
    Config.Resolution r = ctx.config().resolve(name);
    return r.complete() ? r.value() : null;
  }

  // ---------------------------------------------------------------- invocations

  private void invocation(CtInvocation<?> inv) {
    if (inv.getExecutable() == null) {
      return;
    }
    String name = inv.getExecutable().getSimpleName();
    CtExpression<?> target = inv.getTarget();
    if ((REST_TEMPLATE_VERBS.containsKey(name) || REST_TEMPLATE_GENERIC.contains(name))
        && isRestTemplate(target) && !inv.getArguments().isEmpty()) {
      restTemplate(inv, name);
      return;
    }
    if (FLUENT_VERBS.contains(name) && target != null) {
      String kind = fluentClient(target);
      if (kind != null) {
        fluent(inv, name, kind);
        return;
      }
      jaxrs(inv, name);
    }
  }

  private boolean isRestTemplate(CtExpression<?> target) {
    CtTypeReference<?> type = Types.typeOf(target);
    return type != null && REST_TEMPLATES.contains(type.getSimpleName());
  }

  private void restTemplate(CtInvocation<?> inv, String name) {
    String verb = REST_TEMPLATE_VERBS.get(name);
    if (verb == null) {
      verb = inv.getArguments().size() > 1 ? httpMethod(inv.getArguments().get(1)) : null;
      if (verb == null) {
        ctx.diagnostics().warning("HTTP_METHOD_UNRESOLVED", "méthode HTTP de RestTemplate." + name
            + " non déterminée", ctx.source(inv));
      }
    }
    CtExpression<?> urlExpr = inv.getArguments().get(0);
    PartialString evaluated = eval(urlExpr);
    PartialString base = baseUrl(inv.getTarget(), 0);
    List<CtInvocation<?>> sites = evaluated.isComplete() ? List.of() : callSites(inv, evaluated);
    if (sites.isEmpty()) {
      resolveAndAdd(REST_TEMPLATE, verb, withBase(base, text(evaluated)), Callers.of(inv), inv);
      return;
    }
    // URL reçue en paramètre : un appel par site d'appel de la méthode, avec ses arguments
    CtMethod<?> method = inv.getParent(CtMethod.class);
    for (CtInvocation<?> site : sites) {
      Map<CtParameter<?>, PartialString> bindings = new IdentityHashMap<>();
      for (int i = 0; i < method.getParameters().size(); i++) {
        bindings.put(method.getParameters().get(i), eval(site.getArguments().get(i)));
      }
      String url = text(ctx.eval().eval(urlExpr, bindings));
      resolveAndAdd(REST_TEMPLATE, verb, withBase(base, url), Callers.of(site), site);
    }
  }

  /**
   * Appels, dans le dépôt, de la méthode qui contient {@code inv}, quand l'URL évaluée dépend d'un
   * de ses paramètres ; vide sinon. Ordre : fichier, puis position.
   */
  private List<CtInvocation<?>> callSites(CtInvocation<?> inv, PartialString url) {
    CtMethod<?> method = inv.getParent(CtMethod.class);
    if (method == null || inv.getParent(CtLambda.class) != null) {
      return List.of();
    }
    Set<String> params = new HashSet<>();
    method.getParameters().forEach(p -> params.add(p.getSimpleName()));
    boolean dependsOnParameter = url.parts().stream()
        .anyMatch(p -> p instanceof PartialString.Unknown u && params.contains(u.name()));
    if (!dependsOnParameter) {
      return List.of();
    }
    List<CtInvocation<?>> sites = new ArrayList<>();
    for (CtType<?> t : ctx.types().all()) {
      if (t.getDeclaringType() != null) {
        continue;
      }
      for (CtInvocation<?> candidate : t.getElements(new TypeFilter<>(CtInvocation.class))) {
        if (candidate.getExecutable() != null
            && candidate.getExecutable().getSimpleName().equals(method.getSimpleName())
            && candidate.getArguments().size() == method.getParameters().size()
            && declarationOf(candidate) == method) {
          sites.add(candidate);
        }
      }
    }
    sites.sort(Comparator.comparing((CtInvocation<?> c) -> ctx.source(c).file(),
            Comparator.nullsFirst(Comparator.<String>naturalOrder()))
        .thenComparingInt(Provenance::offset));
    return sites;
  }

  private static CtExecutable<?> declarationOf(CtInvocation<?> inv) {
    try {
      return inv.getExecutable().getExecutableDeclaration();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * {@code web_client} ou {@code rest_client} si l'expression est un client (ou sa construction) ;
   * null si la chaîne contient déjà une requête (un {@code .get(0)} en fin de chaîne n'est pas un appel).
   */
  private static String fluentClient(CtExpression<?> e) {
    CtExpression<?> cur = e;
    for (int i = 0; cur != null && i < 20; i++) {
      if (cur instanceof CtInvocation<?> inv && inv.getExecutable() != null
          && (FLUENT_VERBS.contains(inv.getExecutable().getSimpleName())
              || "uri".equals(inv.getExecutable().getSimpleName()))) {
        return null;
      }
      CtTypeReference<?> type = cur instanceof CtTypeAccess<?> ta ? ta.getAccessedType() : Types.typeOf(cur);
      if (type != null && "WebClient".equals(type.getSimpleName())) {
        return WEB_CLIENT;
      }
      if (type != null && "RestClient".equals(type.getSimpleName())) {
        return REST_CLIENT;
      }
      cur = cur instanceof CtInvocation<?> inv ? inv.getTarget() : null;
    }
    return null;
  }

  private void fluent(CtInvocation<?> inv, String name, String kind) {
    String verb = verbOf(inv, name);
    String uri = null;
    if (inv.getParent() instanceof CtInvocation<?> parent && parent.getTarget() == inv
        && parent.getExecutable() != null && "uri".equals(parent.getExecutable().getSimpleName())
        && !parent.getArguments().isEmpty()) {
      CtExpression<?> arg = parent.getArguments().get(0);
      if (arg instanceof CtLambda<?> lambda) {
        StringBuilder sb = new StringBuilder();
        List<CtInvocation<?>> paths = new ArrayList<>(lambda.getElements(new TypeFilter<>(CtInvocation.class)));
        paths.sort(Comparator.comparingInt(Provenance::offset));
        for (CtInvocation<?> p : paths) {
          if (p.getExecutable() != null && "path".equals(p.getExecutable().getSimpleName())
              && p.getArguments().size() == 1) {
            sb.replace(0, sb.length(), join(sb.toString(), text(eval(p.getArguments().get(0)))));
          }
        }
        uri = sb.isEmpty() ? null : sb.toString();
      } else {
        uri = text(eval(arg));
      }
    }
    PartialString base = baseUrl(inv.getTarget(), 0);
    String url = uri == null ? (base == null ? null : text(base)) : withBase(base, uri);
    resolveAndAdd(kind, verb, url, Callers.of(inv), inv);
  }

  private void jaxrs(CtInvocation<?> inv, String name) {
    CtExpression<?> cur = inv.getTarget();
    for (int i = 0; cur instanceof CtInvocation<?> c && i < 20; i++) {
      String n = c.getExecutable() == null ? "" : c.getExecutable().getSimpleName();
      if ("request".equals(n)) {
        PartialString url = webTarget(c.getTarget(), 0);
        if (url != null) {
          resolveAndAdd(JAXRS_CLIENT, verbOf(inv, name), text(url), Callers.of(inv), inv);
        }
        return;
      }
      cur = c.getTarget();
    }
  }

  /** URL d'un WebTarget : {@code target(base)} puis {@code path(...)} successifs ; null si ce n'en est pas un. */
  private PartialString webTarget(CtExpression<?> e, int depth) {
    if (e == null || depth > MAX_DEPTH) {
      return null;
    }
    if (e instanceof CtInvocation<?> inv && inv.getExecutable() != null) {
      String n = inv.getExecutable().getSimpleName();
      if ("target".equals(n) && inv.getArguments().size() == 1) {
        return eval(inv.getArguments().get(0));
      }
      if ("path".equals(n) && inv.getArguments().size() == 1) {
        PartialString base = webTarget(inv.getTarget(), depth + 1);
        return base == null ? null
            : PartialString.lit(join(text(base), text(eval(inv.getArguments().get(0)))));
      }
      if (Set.of("queryParam", "matrixParam", "resolveTemplate", "resolveTemplates", "register", "property")
          .contains(n)) {
        return webTarget(inv.getTarget(), depth + 1);
      }
      return null;
    }
    CtExpression<?> origin = origin(e);
    if (origin != null) {
      return webTarget(origin, depth + 1);
    }
    CtTypeReference<?> type = Types.typeOf(e);
    if (type != null && type.getSimpleName().endsWith("WebTarget")) {
      return PartialString.unknown(e.toString());
    }
    return null;
  }

  /** URL de base d'un client construit dans le dépôt ({@code baseUrl}, {@code rootUri}, {@code create(url)}). */
  private PartialString baseUrl(CtExpression<?> e, int depth) {
    if (e == null || depth > MAX_DEPTH) {
      return null;
    }
    if (e instanceof CtInvocation<?> inv && inv.getExecutable() != null) {
      String n = inv.getExecutable().getSimpleName();
      if (("baseUrl".equals(n) || "rootUri".equals(n)) && inv.getArguments().size() == 1) {
        return eval(inv.getArguments().get(0));
      }
      if ("create".equals(n) && inv.getTarget() instanceof CtTypeAccess<?>) {
        return inv.getArguments().size() == 1 ? eval(inv.getArguments().get(0)) : null;
      }
      return baseUrl(inv.getTarget(), depth + 1);
    }
    CtExpression<?> origin = origin(e);
    return origin == null ? null : baseUrl(origin, depth + 1);
  }

  /** Expression d'initialisation d'un champ (déclaration, sinon première affectation) ou d'une variable locale. */
  private static CtExpression<?> origin(CtExpression<?> e) {
    if (e instanceof CtFieldRead<?> fr && fr.getVariable() != null) {
      CtField<?> f = fr.getVariable().getFieldDeclaration();
      if (f == null) {
        return null;
      }
      if (f.getDefaultExpression() != null) {
        return f.getDefaultExpression();
      }
      CtType<?> owner = f.getDeclaringType();
      if (owner == null) {
        return null;
      }
      List<CtAssignment<?, ?>> assignments = new ArrayList<>();
      for (CtAssignment<?, ?> a : owner.getElements(new TypeFilter<CtAssignment<?, ?>>(CtAssignment.class))) {
        if (a.getAssigned() instanceof CtFieldWrite<?> fw && fw.getVariable() != null
            && f.equals(fw.getVariable().getFieldDeclaration())) {
          assignments.add(a);
        }
      }
      assignments.sort(Comparator.comparingInt(Provenance::offset));
      return assignments.isEmpty() ? null : assignments.get(0).getAssignment();
    }
    if (e instanceof CtVariableRead<?> vr && vr.getVariable() != null
        && vr.getVariable().getDeclaration() instanceof CtLocalVariable<?> lv) {
      return lv.getDefaultExpression();
    }
    return null;
  }

  private String verbOf(CtInvocation<?> inv, String name) {
    if (!"method".equals(name)) {
      return name.toUpperCase(Locale.ROOT);
    }
    String verb = inv.getArguments().isEmpty() ? null : httpMethod(inv.getArguments().get(0));
    if (verb == null) {
      ctx.diagnostics().warning("HTTP_METHOD_UNRESOLVED", "méthode HTTP non déterminée", ctx.source(inv));
    }
    return verb;
  }

  /** {@code HttpMethod.PUT}, {@code "PUT"} ou {@code HttpMethod.valueOf("PUT")} ; null sinon. */
  private String httpMethod(CtExpression<?> e) {
    String v = Annotations.enumConstant(e);
    if (v == null) {
      v = ctx.eval().constant(e);
    }
    if (v == null && e instanceof CtInvocation<?> inv && inv.getArguments().size() == 1) {
      v = ctx.eval().constant(inv.getArguments().get(0));
    }
    v = v == null ? null : v.toUpperCase(Locale.ROOT);
    return v != null && SpringMappings.HTTP.contains(v) ? v : null;
  }

  // ---------------------------------------------------------------- résolution

  private void resolveAndAdd(String client, String verb, String raw, String caller, CtElement at) {
    Source source = ctx.source(at);
    if (raw == null || raw.isEmpty()) {
      ctx.diagnostics().warning("URL_UNRESOLVED", "URL d'appel inconnue (" + client + ")", source);
      add(client, verb, null, null, null, null, caller, at);
      return;
    }
    String cleanRaw = Secrets.sanitizeUrl(raw);
    Config.Resolution r = ctx.config().resolve(cleanRaw);
    String value = formatVariables(Secrets.sanitizeUrl(r.value()));
    String host = host(value);
    String resolved = r.complete() && host != null ? value : null;
    String path = path(resolved != null ? resolved : value);
    String target = null;
    if (resolved == null) {
      ctx.diagnostics().warning("URL_UNRESOLVED", "URL non résolue : " + cleanRaw
          + (r.missing().isEmpty() ? "" : " (inconnu : " + String.join(", ", r.missing()) + ")"), source);
    } else {
      target = targetApp(host);
      if (target == null) {
        ctx.diagnostics().info("TARGET_APP_UNKNOWN", "application cible non déduite de l'hôte " + host, source);
      }
    }
    add(client, verb, cleanRaw, resolved, path, target, caller, at);
  }

  private void add(String client, String verb, String raw, String resolved, String path, String target,
      String caller, CtElement at) {
    String id = ctx.appId() + ":" + (verb == null ? "?" : verb) + ":" + (target == null ? "?" : target) + ":"
        + (path != null ? path : raw != null ? raw : "?") + (caller == null ? "" : "@" + caller);
    out.add(new Call(id, client, verb, raw, resolved, path, target, caller, ctx.source(at)));
  }

  /**
   * Spécificateurs {@code %s}, {@code %d} restés dans l'URL (format lu dans la configuration puis
   * passé à {@code String.format}) : variables {@code {argN}}, N étant la position de l'argument.
   */
  static String formatVariables(String url) {
    if (url == null || !url.contains("%")) {
      return url;
    }
    Matcher m = FORMAT_SPECIFIER.matcher(url);
    StringBuilder sb = new StringBuilder();
    int next = 0;
    while (m.find()) {
      int n = m.group(1) != null ? Integer.parseInt(m.group(1)) : ++next;
      m.appendReplacement(sb, "{arg" + n + "}");
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** Hôte littéral d'une URL absolue http(s), sans port ; null sinon. */
  static String host(String url) {
    Matcher m = Pattern.compile("^(?i:https?|lb)://([^/:?#]+)").matcher(url == null ? "" : url);
    if (!m.find()) {
      return null;
    }
    String h = m.group(1);
    return h.contains("{") || h.contains("$") || h.contains("*") ? null : h.toLowerCase(Locale.ROOT);
  }

  /**
   * Chemin d'une URL (sans requête ni fragment, variables normalisées) : après l'hôte pour une URL
   * absolue, après le premier morceau inconnu sinon ; null s'il n'est pas identifiable.
   */
  static String path(String url) {
    if (url == null) {
      return null;
    }
    String rest;
    int scheme = url.indexOf("://");
    if (scheme >= 0 && url.substring(0, scheme).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
      int slash = url.indexOf('/', scheme + 3);
      rest = slash < 0 ? "/" : url.substring(slash);
    } else if (url.startsWith("/")) {
      rest = url;
    } else if (url.startsWith("${") || url.startsWith("{")) {
      int end = closing(url, url.indexOf('{'));
      rest = end < 0 ? null : url.substring(end + 1);
      if (rest != null && rest.isEmpty()) {
        return null;
      }
      if (rest != null && !rest.startsWith("/")) {
        return null;
      }
    } else {
      return null;
    }
    int q = indexOfAny(rest, '?', '#');
    if (q >= 0) {
      rest = rest.substring(0, q);
    }
    rest = EndpointExtractor.normalizeVariables(rest);
    return rest.isEmpty() ? "/" : rest;
  }

  /** Nom court d'hôte, ou premier label d'un hôte de service Kubernetes ; null sinon. */
  static String targetApp(String host) {
    if (host == null || "localhost".equals(host)) {
      return null;
    }
    if (!host.contains(".")) {
      return host.matches("[a-z0-9][a-z0-9-]*") ? host : null;
    }
    Matcher m = SVC_HOST.matcher(host);
    return m.matches() ? m.group(1) : null;
  }

  // ---------------------------------------------------------------- utilitaires

  private PartialString eval(CtExpression<?> e) {
    return e == null ? PartialString.EMPTY : ctx.eval().eval(e);
  }

  private static String text(PartialString ps) {
    return ps == null ? "" : ps.render();
  }

  private static String withBase(PartialString base, String url) {
    boolean relative = url == null || url.isEmpty() || url.startsWith("/");
    if (!relative || base == null) {
      return url;
    }
    return join(text(base), url);
  }

  /** Concatène des morceaux d'URL avec un seul « / » entre eux. */
  static String join(String... parts) {
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (p == null || p.isEmpty()) {
        continue;
      }
      if (sb.isEmpty()) {
        sb.append(p);
        continue;
      }
      boolean left = sb.charAt(sb.length() - 1) == '/';
      boolean right = p.startsWith("/");
      if (left && right) {
        sb.append(p, 1, p.length());
      } else if (!left && !right) {
        sb.append('/').append(p);
      } else {
        sb.append(p);
      }
    }
    return sb.toString();
  }

  private static int closing(String s, int open) {
    int level = 0;
    for (int k = open; k >= 0 && k < s.length(); k++) {
      char c = s.charAt(k);
      if (c == '{') {
        level++;
      } else if (c == '}' && --level == 0) {
        return k;
      }
    }
    return -1;
  }

  private static int indexOfAny(String s, char a, char b) {
    int i = s.indexOf(a);
    int j = s.indexOf(b);
    return i < 0 ? j : j < 0 ? i : Math.min(i, j);
  }
}
