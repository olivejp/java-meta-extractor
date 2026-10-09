package fr.cafat.meta.extract.rest;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.model.Endpoint;
import fr.cafat.meta.scan.WebModule;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.PartialString;
import fr.cafat.meta.spoon.Provenance;
import fr.cafat.meta.spoon.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtTypeReference;

/**
 * Endpoints exposés : contrôleurs Spring MVC et ressources JAX-RS.
 *
 * <p>Seules les classes concrètes sont retenues ; les mappings déclarés sur une superclasse ou une
 * interface du dépôt sont hérités. Chemin = contexte du module web + préfixe (servlet Spring ou
 * application JAX-RS) + préfixe de classe + chemin de méthode ; variables de chemin réduites à
 * {@code {nom}} (expression régulière retirée). Un endpoint par couple méthode HTTP × chemin.
 */
public final class EndpointExtractor {

  public static final String SPRING_MVC = "spring_mvc";
  public static final String JAX_RS = "jax_rs";

  private static final Set<String> STEREOTYPE = Set.of("org.springframework.stereotype");
  private static final List<String> HTTP = SpringMappings.HTTP;
  private static final Set<String> JAXRS_PARAMS = Set.of("PathParam", "QueryParam", "HeaderParam", "CookieParam",
      "FormParam", "MatrixParam", "BeanParam", "Context", "Suspended");
  private static final int MAX_DEPTH = 20;

  private final ExtractionContext ctx;
  private final Map<WebModule, List<String>> applicationPaths = new HashMap<>();
  private final List<Endpoint> out = new ArrayList<>();

  /**
   * Prépare l'extraction des endpoints exposés.
   *
   * @param ctx contexte d'extraction de l'application (modèle, configuration, diagnostics)
   */
  public EndpointExtractor(ExtractionContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Endpoints des contrôleurs Spring MVC et des ressources JAX-RS concrets.
   *
   * @return un endpoint par couple méthode HTTP × chemin, ids non suffixés ; liste vide si aucun
   */
  public List<Endpoint> extract() {
    for (CtType<?> t : ctx.types().all()) {
      if (!(t instanceof CtClass<?> c) || c.isAbstract() || c.isAnonymous() || c.isLocalType()) {
        continue;
      }
      List<CtType<?>> hierarchy = hierarchy(c);
      if (isSpringController(hierarchy)) {
        spring(c, hierarchy);
      } else if (classAnnotation(hierarchy, Annotations.JAXRS, "Path") != null && !isJaxrsApplication(c)) {
        jaxrs(c, hierarchy);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- Spring MVC

  private boolean isSpringController(List<CtType<?>> hierarchy) {
    CtType<?> c = hierarchy.get(0);
    return Annotations.hasMeta(c, ctx.types(), Annotations.SPRING_WEB, "RestController")
        || Annotations.hasMeta(c, ctx.types(), STEREOTYPE, "Controller");
  }

  private void spring(CtClass<?> c, List<CtType<?>> hierarchy) {
    boolean bodyForAll = Annotations.hasMeta(c, ctx.types(), Annotations.SPRING_WEB, "RestController");
    for (CtType<?> t : hierarchy) {
      bodyForAll |= Annotations.hasMeta(t, ctx.types(), Annotations.SPRING_WEB, "ResponseBody");
    }
    CtAnnotation<?> classMapping = classAnnotation(hierarchy, Annotations.SPRING_WEB, "RequestMapping");
    List<String> classPaths = paths(classMapping, c);
    List<String> classVerbs = SpringMappings.verbs(classMapping);
    WebModule web = ctx.webModule(c);
    for (List<CtMethod<?>> overrides : methods(hierarchy).values()) {
      CtMethod<?> impl = overrides.get(0);
      CtMethod<?> mapped = null;
      CtAnnotation<?> mapping = null;
      for (CtMethod<?> m : overrides) {
        mapping = SpringMappings.find(m);
        if (mapping != null) {
          mapped = m;
          break;
        }
      }
      if (mapping == null || !isCallable(impl)) {
        continue;
      }
      if (!bodyForAll && !hasResponseBody(overrides) && !Types.isNamed(impl.getType(), "ResponseEntity",
          "HttpEntity")) {
        continue;
      }
      List<String> verbs = SpringMappings.verbs(mapping);
      if (verbs.isEmpty()) {
        verbs = classVerbs.isEmpty() ? List.of("ANY") : classVerbs;
      }
      String request = null;
      for (int i = 0; i < impl.getParameters().size() && request == null; i++) {
        for (CtMethod<?> m : overrides) {
          if (i < m.getParameters().size()
              && Annotations.has(m.getParameters().get(i), Annotations.SPRING_WEB, "RequestBody")) {
            request = Types.render(impl.getParameters().get(i).getType());
            break;
          }
        }
      }
      List<String> methodPaths = paths(mapping, mapped);
      for (String cp : classPaths) {
        for (String mp : methodPaths) {
          String path = path(web.contextPath(), web.mvcPath(), cp, mp);
          for (String v : verbs) {
            add(SPRING_MVC, v, path, c, impl, request, response(impl.getType()));
          }
        }
      }
    }
  }

  private static boolean hasResponseBody(List<CtMethod<?>> overrides) {
    for (CtMethod<?> m : overrides) {
      if (Annotations.has(m, Annotations.SPRING_WEB, "ResponseBody")) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- JAX-RS

  private boolean isJaxrsApplication(CtClass<?> c) {
    if (Annotations.has(c, Annotations.JAXRS, "ApplicationPath")) {
      return true;
    }
    CtTypeReference<?> sup = c.getSuperclass();
    return sup != null && "Application".equals(sup.getSimpleName());
  }

  private void jaxrs(CtClass<?> c, List<CtType<?>> hierarchy) {
    List<String> classPaths = paths(classAnnotation(hierarchy, Annotations.JAXRS, "Path"), c);
    WebModule web = ctx.webModule(c);
    List<String> bases = !web.jaxrsMappings().isEmpty() ? web.jaxrsMappings() : applicationPaths(web);
    for (List<CtMethod<?>> overrides : methods(hierarchy).values()) {
      CtMethod<?> impl = overrides.get(0);
      CtMethod<?> mapped = null;
      for (CtMethod<?> m : overrides) {
        if (hasJaxrsAnnotation(m)) {
          mapped = m;
          break;
        }
      }
      if (mapped == null || !isCallable(impl)) {
        continue;
      }
      List<String> verbs = new ArrayList<>();
      for (String v : HTTP) {
        if (Annotations.has(mapped, Annotations.JAXRS, v)) {
          verbs.add(v);
        }
      }
      CtAnnotation<?> methodPath = Annotations.find(mapped, Annotations.JAXRS, "Path");
      if (verbs.isEmpty()) {
        if (methodPath != null) {
          ctx.diagnostics().info("SUBRESOURCE_LOCATOR_IGNORED", "sous-ressource JAX-RS "
              + c.getQualifiedName() + "#" + impl.getSimpleName() + " non suivie", ctx.source(impl));
        }
        continue;
      }
      String request = null;
      for (CtParameter<?> p : mapped.getParameters()) {
        if (p.getAnnotations().stream().noneMatch(a -> JAXRS_PARAMS.contains(a.getAnnotationType().getSimpleName()))) {
          request = Types.render(p.getType());
          break;
        }
      }
      String response = Types.isNamed(impl.getType(), "Response") ? null : response(impl.getType());
      List<String> methodPaths = methodPath == null ? List.of("") : paths(methodPath, mapped);
      for (String base : bases) {
        for (String cp : classPaths) {
          for (String mp : methodPaths) {
            String path = path(web.contextPath(), base, cp, mp);
            for (String v : verbs) {
              add(JAX_RS, v, path, c, impl, request, response);
            }
          }
        }
      }
    }
  }

  private static boolean hasJaxrsAnnotation(CtMethod<?> m) {
    if (Annotations.has(m, Annotations.JAXRS, "Path")) {
      return true;
    }
    for (String v : HTTP) {
      if (Annotations.has(m, Annotations.JAXRS, v)) {
        return true;
      }
    }
    return false;
  }

  /** Valeurs de {@code @ApplicationPath} des classes du même module web ("" à défaut). */
  private List<String> applicationPaths(WebModule web) {
    return applicationPaths.computeIfAbsent(web, w -> {
      Set<String> out = new LinkedHashSet<>();
      for (CtType<?> t : ctx.types().all()) {
        CtAnnotation<?> a = Annotations.find(t, Annotations.JAXRS, "ApplicationPath");
        if (a != null && ctx.webModule(t).equals(w)) {
          out.addAll(paths(a, t));
        }
      }
      return out.isEmpty() ? List.of("") : List.copyOf(out);
    });
  }

  // ---------------------------------------------------------------- commun

  private void add(String framework, String verb, String path, CtClass<?> c, CtMethod<?> impl, String request,
      String response) {
    String id = ctx.appId() + ":" + verb + ":" + path;
    out.add(new Endpoint(id, framework, verb, path, c.getQualifiedName() + "#" + impl.getSimpleName(), request,
        response, ctx.source(impl)));
  }

  private static boolean isCallable(CtMethod<?> m) {
    return !m.hasModifier(ModifierKind.PRIVATE) && !m.hasModifier(ModifierKind.STATIC);
  }

  private static String response(CtTypeReference<?> type) {
    CtTypeReference<?> t = Types.unwrapResponse(type);
    return t == null || Types.isVoid(t) ? null : Types.render(t);
  }

  /** La classe, ses superclasses du dépôt, puis toutes leurs interfaces du dépôt. */
  private List<CtType<?>> hierarchy(CtClass<?> c) {
    Set<CtType<?>> classes = new LinkedHashSet<>();
    CtType<?> cur = c;
    for (int i = 0; cur != null && i < MAX_DEPTH && classes.add(cur); i++) {
      cur = cur.getSuperclass() == null ? null : ctx.types().resolve(cur.getSuperclass());
    }
    Set<CtType<?>> all = new LinkedHashSet<>(classes);
    List<CtType<?>> todo = new ArrayList<>(classes);
    for (int i = 0; i < todo.size() && i < 200; i++) {
      for (CtTypeReference<?> itf : todo.get(i).getSuperInterfaces()) {
        CtType<?> it = ctx.types().resolve(itf);
        if (it != null && all.add(it)) {
          todo.add(it);
        }
      }
    }
    return new ArrayList<>(all);
  }

  private static CtAnnotation<?> classAnnotation(List<CtType<?>> hierarchy, Set<String> packages, String name) {
    for (CtType<?> t : hierarchy) {
      CtAnnotation<?> a = Annotations.find(t, packages, name);
      if (a != null) {
        return a;
      }
    }
    return null;
  }

  /**
   * Méthodes par signature (nom + types des paramètres), dans l'ordre de la hiérarchie : la
   * première est l'implémentation retenue, les suivantes les déclarations redéfinies.
   */
  private static Map<String, List<CtMethod<?>>> methods(List<CtType<?>> hierarchy) {
    Map<String, List<CtMethod<?>>> out = new LinkedHashMap<>();
    for (CtType<?> t : hierarchy) {
      List<CtMethod<?>> own = new ArrayList<>();
      for (CtTypeMember m : t.getTypeMembers()) {
        if (m instanceof CtMethod<?> method) {
          own.add(method);
        }
      }
      own.sort(Comparator.comparingInt(Provenance::offset));
      for (CtMethod<?> m : own) {
        out.computeIfAbsent(signature(m), k -> new ArrayList<>()).add(m);
      }
    }
    return out;
  }

  private static String signature(CtMethod<?> m) {
    StringBuilder sb = new StringBuilder(m.getSimpleName()).append('(');
    for (CtParameter<?> p : m.getParameters()) {
      sb.append(p.getType() == null ? "?" : p.getType().getSimpleName()).append(',');
    }
    return sb.append(')').toString();
  }

  /** Chemins d'une annotation ({@code path}/{@code value}) ; [""] si aucun. */
  private List<String> paths(CtAnnotation<?> a, CtElement owner) {
    List<String> out = new ArrayList<>();
    CtExpression<?> e = SpringMappings.pathExpression(a);
    if (e != null) {
      for (PartialString ps : ctx.eval().evalAll(e)) {
        out.add(text(ps, owner));
      }
    }
    return out.isEmpty() ? List.of("") : out;
  }

  /** Texte d'un chemin : placeholders résolus par la configuration ; inconnu ⇒ {@code ${nom}} + warning. */
  private String text(PartialString ps, CtElement owner) {
    Config config = ctx.config();
    StringBuilder sb = new StringBuilder();
    Set<String> missing = new LinkedHashSet<>();
    for (PartialString.Part p : ps.parts()) {
      if (p instanceof PartialString.Lit l) {
        Config.Resolution r = config.resolve(l.text());
        sb.append(r.value());
        missing.addAll(r.missing());
      } else if (p instanceof PartialString.Unknown u) {
        sb.append("${").append(u.name()).append('}');
        missing.add(u.name());
      }
    }
    if (!missing.isEmpty()) {
      ctx.diagnostics().warning("ENDPOINT_PATH_UNRESOLVED", "chemin d'endpoint partiellement inconnu : "
          + String.join(", ", missing), ctx.source(owner));
    }
    return sb.toString();
  }

  /**
   * Concatène les segments, normalise les « / » et les variables de chemin.
   *
   * @param segments morceaux de chemin, dans l'ordre ; segments null ou vides ignorés
   * @return chemin commençant par « / », sans « / » final ; « / » si tous les segments sont vides
   */
  public static String path(String... segments) {
    StringBuilder sb = new StringBuilder();
    for (String s : segments) {
      String x = s == null ? "" : s.strip();
      if (x.isEmpty()) {
        continue;
      }
      if (!x.startsWith("/")) {
        sb.append('/');
      }
      sb.append(x);
    }
    String p = normalizeVariables(sb.toString()).replaceAll("/{2,}", "/");
    if (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    return p.isEmpty() ? "/" : p;
  }

  /**
   * {@code {id:\d+}} ou {@code {id: [0-9]+}} ⇒ {@code {id}} ; les placeholders {@code ${...}} sont gardés.
   *
   * @param path chemin d'URL, obligatoire
   * @return chemin aux variables réduites à leur nom ; accolade non fermée gardée telle quelle
   */
  public static String normalizeVariables(String path) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (i < path.length()) {
      char ch = path.charAt(i);
      if (ch == '{' && (i == 0 || path.charAt(i - 1) != '$')) {
        int end = matching(path, i);
        if (end > 0) {
          String inner = path.substring(i + 1, end);
          int colon = inner.indexOf(':');
          sb.append('{').append((colon < 0 ? inner : inner.substring(0, colon)).strip()).append('}');
          i = end + 1;
          continue;
        }
      }
      sb.append(ch);
      i++;
    }
    return sb.toString();
  }

  private static int matching(String s, int open) {
    int level = 0;
    for (int k = open; k < s.length(); k++) {
      char c = s.charAt(k);
      if (c == '{') {
        level++;
      } else if (c == '}' && --level == 0) {
        return k;
      }
    }
    return -1;
  }
}
