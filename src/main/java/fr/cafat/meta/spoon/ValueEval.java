package fr.cafat.meta.spoon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtCodeSnippetExpression;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtTextBlock;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Évaluation statique d'expressions chaînes, sans exécution. Les espaces réservés de configuration
 * sont conservés ({@code ${cle}}) pour être résolus par la configuration ; tout ce qui n'est pas
 * calculable devient un morceau inconnu.
 */
public final class ValueEval {

  private static final int MAX_DEPTH = 16;
  private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[-#+ 0,(<]*\\d*(\\.\\d+)?([a-zA-Z%])");
  private static final Set<String> IDENTITY_METHODS = Set.of("trim", "strip", "intern", "toString",
      "stripIndent", "trimIndent", "trimMargin", "build", "encode", "toUri", "toUriString",
      "toURI", "normalize", "expand", "buildAndExpand", "toUriTemplate", "requireNonNull",
      "orEmpty", "uri");
  private static final Set<String> STRING_BUILDERS = Set.of("StringBuilder", "StringBuffer",
      "StringJoiner");
  private static final Set<String> ENVIRONMENTS = Set.of("Environment", "ConfigurableEnvironment",
      "PropertyResolver", "StandardEnvironment", "AbstractEnvironment");

  private final TypeIndex index;
  // Collections par identité : l'égalité des éléments Spoon est structurelle (deux champs
  // « private final String url; » de classes différentes sont égaux) et son hashCode parcourt l'arbre.
  private final Map<CtElement, PartialString> cache = new IdentityHashMap<>();

  public ValueEval(TypeIndex index) {
    this.index = index;
  }

  /** Contexte d'évaluation : profondeur, liaisons des paramètres, déclarations en cours. */
  private record Ctx(int depth, Map<CtParameter<?>, PartialString> bindings, Set<CtElement> visiting) {

    Ctx deeper() {
      return new Ctx(depth + 1, bindings, visiting);
    }

    Ctx bind(Map<CtParameter<?>, PartialString> b) {
      return new Ctx(depth + 1, b, visiting);
    }
  }

  private static Set<CtElement> identitySet() {
    return Collections.newSetFromMap(new IdentityHashMap<>());
  }

  public PartialString eval(CtExpression<?> e) {
    return eval(e, new Ctx(0, Map.of(), identitySet()));
  }

  /** Évalue chaque élément d'un tableau (ou l'expression seule). */
  public List<PartialString> evalAll(CtExpression<?> e) {
    List<PartialString> out = new ArrayList<>();
    for (CtExpression<?> el : Annotations.flatten(e)) {
      out.add(eval(el));
    }
    return out;
  }

  /** Valeur littérale complète, sinon null. */
  public String constant(CtExpression<?> e) {
    if (e == null) {
      return null;
    }
    return eval(e).valueOrNull();
  }

  private PartialString eval(CtExpression<?> e, Ctx ctx) {
    if (e == null) {
      return PartialString.unknown("null");
    }
    if (ctx.depth > MAX_DEPTH) {
      return PartialString.unknown(nameOf(e));
    }
    try {
      return doEval(e, ctx);
    } catch (RuntimeException ex) {
      return PartialString.unknown(nameOf(e));
    }
  }

  private PartialString doEval(CtExpression<?> e, Ctx ctx) {
    if (e instanceof CtTextBlock tb) {
      return PartialString.lit(tb.getValue());
    }
    if (e instanceof CtLiteral<?> lit) {
      Object v = lit.getValue();
      return v == null ? PartialString.unknown("null") : PartialString.lit(String.valueOf(v));
    }
    if (e instanceof CtBinaryOperator<?> bin) {
      if (bin.getKind() == BinaryOperatorKind.PLUS) {
        return PartialString.concat(eval(bin.getLeftHandOperand(), ctx.deeper()),
            eval(bin.getRightHandOperand(), ctx.deeper()));
      }
      return PartialString.unknown(nameOf(e));
    }
    if (e instanceof CtConditional<?> cond) {
      PartialString a = eval(cond.getThenExpression(), ctx.deeper());
      PartialString b = eval(cond.getElseExpression(), ctx.deeper());
      if (a.isComplete() && a.equals(b)) {
        return a;
      }
      return PartialString.unknown("cond").markDynamic();
    }
    if (e instanceof CtFieldRead<?> fr) {
      return evalField(fr, ctx);
    }
    if (e instanceof CtVariableRead<?> vr) {
      return evalVariable(vr, ctx);
    }
    if (e instanceof CtInvocation<?> inv) {
      return evalInvocation(inv, ctx);
    }
    if (e instanceof CtConstructorCall<?> cc) {
      return evalConstructor(cc, ctx);
    }
    if (e instanceof CtNewArray<?> arr && arr.getElements().size() == 1) {
      return eval(arr.getElements().get(0), ctx.deeper());
    }
    if (e instanceof CtCodeSnippetExpression<?>) {
      return PartialString.unknown("?");
    }
    return PartialString.unknown(nameOf(e));
  }

  // ---------------------------------------------------------------- variables et champs

  private PartialString evalField(CtFieldRead<?> fr, Ctx ctx) {
    CtVariableReference<?> ref = fr.getVariable();
    String name = ref == null ? "?" : ref.getSimpleName();
    CtField<?> field = fieldDeclaration(fr);
    if (field == null) {
      return PartialString.unknown(name);
    }
    return evalFieldDeclaration(field, ctx);
  }

  /** Déclaration du champ lu, retrouvée par nom en noClasspath ; null si elle est hors du dépôt. */
  public CtField<?> field(CtFieldRead<?> fr) {
    return fieldDeclaration(fr);
  }

  private CtField<?> fieldDeclaration(CtFieldRead<?> fr) {
    CtVariableReference<?> ref = fr.getVariable();
    if (ref == null) {
      return null;
    }
    CtVariable<?> decl = ref.getDeclaration();
    if (decl instanceof CtField<?> f) {
      return f;
    }
    // En noClasspath la déclaration peut manquer : recherche par nom dans le type ciblé
    CtTypeReference<?> owner = null;
    if (fr.getTarget() instanceof CtTypeAccess<?> ta) {
      owner = ta.getAccessedType();
    } else if (ref instanceof spoon.reflect.reference.CtFieldReference<?> fref) {
      owner = fref.getDeclaringType();
    }
    CtType<?> type = owner == null ? null : index.resolve(owner);
    if (type == null && fr.getTarget() == null || fr.getTarget() instanceof spoon.reflect.code.CtThisAccess<?>) {
      type = fr.getParent(CtType.class);
    }
    for (CtType<?> t = type; t != null; t = t.getDeclaringType()) {
      CtField<?> f = t.getField(ref.getSimpleName());
      if (f != null) {
        return f;
      }
    }
    return null;
  }

  /** Valeur d'un champ : @Value, constante, propriété de configuration, injection par constructeur. */
  public PartialString evalFieldDeclaration(CtField<?> field) {
    return evalFieldDeclaration(field, new Ctx(0, Map.of(), identitySet()));
  }

  private PartialString evalFieldDeclaration(CtField<?> field, Ctx ctx) {
    PartialString cached = cache.get(field);
    if (cached != null) {
      return cached;
    }
    if (!ctx.visiting.add(field)) {
      return PartialString.unknown(field.getSimpleName());
    }
    try {
      PartialString v = computeField(field, ctx);
      cache.put(field, v);
      return v;
    } finally {
      ctx.visiting.remove(field);
    }
  }

  private PartialString computeField(CtField<?> field, Ctx ctx) {
    PartialString injected = valueAnnotation(field, ctx);
    if (injected != null) {
      return injected;
    }
    CtType<?> owner = field.getDeclaringType();
    String prefix = configurationPrefix(owner);
    if (prefix != null && !field.isStatic()) {
      String key = prefix + "." + kebab(field.getSimpleName());
      PartialString def = field.getDefaultExpression() == null ? null
          : eval(field.getDefaultExpression(), ctx.deeper());
      if (def != null && def.isComplete()) {
        return PartialString.lit("${" + key + ":" + def.render() + "}");
      }
      return PartialString.lit("${" + key + "}");
    }
    if (field.getDefaultExpression() != null) {
      return eval(field.getDefaultExpression(), ctx.deeper());
    }
    PartialString assigned = assignedInType(field, ctx);
    return assigned != null ? assigned : PartialString.unknown(field.getSimpleName());
  }

  /** Affectation unique {@code this.f = x} dans un constructeur ou un setter. */
  private PartialString assignedInType(CtField<?> field, Ctx ctx) {
    CtType<?> owner = field.getDeclaringType();
    if (owner == null) {
      return null;
    }
    List<CtAssignment<?, ?>> assignments = new ArrayList<>();
    for (CtAssignment<?, ?> a : owner.getElements(new TypeFilter<CtAssignment<?, ?>>(CtAssignment.class))) {
      if (a instanceof CtOperatorAssignment<?, ?>) {
        continue;
      }
      if (a.getAssigned() instanceof CtFieldWrite<?> fw && fw.getVariable() != null
          && field.getSimpleName().equals(fw.getVariable().getSimpleName())
          && (fw.getVariable().getDeclaration() == null || fw.getVariable().getDeclaration() == field)) {
        assignments.add(a);
      }
    }
    if (assignments.isEmpty()) {
      return null;
    }
    assignments.sort(Comparator.comparingInt(Provenance::offset));
    List<PartialString> values = new ArrayList<>();
    for (CtAssignment<?, ?> a : assignments) {
      CtExpression<?> rhs = a.getAssignment();
      CtExecutable<?> exec = a.getParent(CtExecutable.class);
      if (rhs instanceof CtVariableRead<?> vr && vr.getVariable().getDeclaration() instanceof CtParameter<?> p) {
        PartialString v = valueAnnotation(p, ctx);
        if (v == null && exec instanceof CtMethod<?> m) {
          v = valueAnnotation(m, ctx);
        }
        values.add(v != null ? v : PartialString.unknown(p.getSimpleName()));
      } else {
        values.add(eval(rhs, ctx.deeper()));
      }
    }
    PartialString first = values.get(0);
    for (PartialString v : values) {
      if (!v.equals(first)) {
        return PartialString.unknown(field.getSimpleName()).markDynamic();
      }
    }
    return first;
  }

  /** Valeur de {@code @Value("...")} portée par l'élément, ou null. */
  private PartialString valueAnnotation(CtElement element, Ctx ctx) {
    CtAnnotation<?> a = Annotations.find(element,
        Set.of("org.springframework.beans.factory.annotation"), "Value");
    if (a == null) {
      a = Annotations.find(element, Set.of("org.eclipse.microprofile.config.inject"), "ConfigProperty");
      if (a != null) {
        String name = constant(Annotations.value(a, "name"));
        String def = constant(Annotations.value(a, "defaultValue"));
        if (name == null) {
          return null;
        }
        return PartialString.lit("${" + name + (def == null ? "" : ":" + def) + "}");
      }
      return null;
    }
    CtExpression<?> v = Annotations.value(a, "value");
    return v == null ? null : eval(v, ctx.deeper());
  }

  /** Préfixe {@code @ConfigurationProperties} d'un type, ou null. */
  public String configurationPrefix(CtType<?> type) {
    if (type == null) {
      return null;
    }
    CtAnnotation<?> a = Annotations.find(type,
        Set.of("org.springframework.boot.context.properties"), "ConfigurationProperties");
    if (a == null) {
      return null;
    }
    String p = constant(Annotations.firstValue(a, "prefix", "value"));
    return p == null || p.isEmpty() ? null : p;
  }

  private PartialString evalVariable(CtVariableRead<?> vr, Ctx ctx) {
    CtVariableReference<?> ref = vr.getVariable();
    String name = ref == null ? "?" : ref.getSimpleName();
    CtVariable<?> decl = ref == null ? null : ref.getDeclaration();
    if (decl instanceof CtParameter<?> p) {
      PartialString bound = ctx.bindings.get(p);
      if (bound != null) {
        return bound;
      }
      PartialString v = valueAnnotation(p, ctx);
      return v != null ? v : PartialString.unknown(name);
    }
    if (decl instanceof CtLocalVariable<?> local) {
      return evalLocal(local, vr, ctx);
    }
    if (decl instanceof CtField<?> f) {
      return evalFieldDeclaration(f, ctx);
    }
    return PartialString.unknown(name);
  }

  /**
   * Valeur d'une variable locale au point d'usage : initialiseur, puis affectations et
   * concaténations ({@code +=}) antérieures, dans l'ordre du source. Une concaténation ou une
   * affectation conditionnelle rend la chaîne dynamique.
   */
  private PartialString evalLocal(CtLocalVariable<?> local, CtElement use, Ctx ctx) {
    if (!ctx.visiting.add(local)) {
      return PartialString.unknown(local.getSimpleName());
    }
    try {
      if (isStringBuilder(local.getType())) {
        return evalBuilder(local, use, ctx);
      }
      PartialString value = local.getDefaultExpression() == null
          ? PartialString.unknown(local.getSimpleName())
          : eval(local.getDefaultExpression(), ctx.deeper());
      CtElement scope = local.getParent();
      int useOffset = Provenance.offset(use);
      List<CtAssignment<?, ?>> writes = new ArrayList<>();
      for (CtAssignment<?, ?> a : scope.getElements(new TypeFilter<CtAssignment<?, ?>>(CtAssignment.class))) {
        if (a.getAssigned() instanceof CtVariableAccess<?> va && va.getVariable() != null
            && va.getVariable().getDeclaration() == local
            && (useOffset < 0 || Provenance.offset(a) < useOffset)) {
          writes.add(a);
        }
      }
      writes.sort(Comparator.comparingInt(Provenance::offset));
      for (CtAssignment<?, ?> a : writes) {
        PartialString rhs = eval(a.getAssignment(), ctx.deeper());
        boolean conditional = isConditional(a, scope);
        if (a instanceof CtOperatorAssignment<?, ?> op && op.getKind() == BinaryOperatorKind.PLUS) {
          value = PartialString.concat(value, rhs).markDynamic();
        } else if (conditional) {
          value = value.markDynamic();
        } else {
          value = rhs;
        }
      }
      return value;
    } finally {
      ctx.visiting.remove(local);
    }
  }

  private static boolean isStringBuilder(CtTypeReference<?> t) {
    return t != null && STRING_BUILDERS.contains(t.getSimpleName());
  }

  /** Contenu d'un StringBuilder local : constructeur puis appels append() antérieurs à l'usage. */
  private PartialString evalBuilder(CtLocalVariable<?> local, CtElement use, Ctx ctx) {
    PartialString value = PartialString.EMPTY;
    String separator = null;
    if (local.getDefaultExpression() instanceof CtConstructorCall<?> cc) {
      if ("StringJoiner".equals(cc.getType().getSimpleName())) {
        if (!cc.getArguments().isEmpty()) {
          separator = constant(cc.getArguments().get(0));
        }
      } else if (!cc.getArguments().isEmpty() && !isNumeric(cc.getArguments().get(0))) {
        value = eval(cc.getArguments().get(0), ctx.deeper());
      }
    } else if (local.getDefaultExpression() != null) {
      value = evalBuilderChain(local.getDefaultExpression(), ctx);
    }
    CtElement scope = local.getParent();
    int useOffset = Provenance.offset(use);
    List<CtInvocation<?>> appends = new ArrayList<>();
    for (CtInvocation<?> inv : scope.getElements(new TypeFilter<CtInvocation<?>>(CtInvocation.class))) {
      String n = inv.getExecutable().getSimpleName();
      if ((n.equals("append") || n.equals("add") || n.equals("insert") || n.equals("setLength"))
          && rootVariable(inv) == local
          && (useOffset < 0 || Provenance.offset(inv) < useOffset)) {
        appends.add(inv);
      }
    }
    appends.sort(Comparator.comparingInt(Provenance::offset));
    boolean first = value.isEmpty();
    for (CtInvocation<?> inv : appends) {
      String n = inv.getExecutable().getSimpleName();
      if (!n.equals("append") && !n.equals("add")) {
        value = value.markDynamic();
        continue;
      }
      PartialString piece = inv.getArguments().size() == 1
          ? eval(inv.getArguments().get(0), ctx.deeper())
          : PartialString.unknown("?");
      if (separator != null && !first) {
        piece = PartialString.concat(PartialString.lit(separator), piece);
      }
      first = false;
      value = PartialString.concat(value, piece);
      if (isConditional(inv, scope)) {
        value = value.markDynamic();
      }
    }
    return value;
  }

  /** Chaîne {@code new StringBuilder(a).append(b)...} évaluée de bout en bout. */
  private PartialString evalBuilderChain(CtExpression<?> e, Ctx ctx) {
    if (e instanceof CtInvocation<?> inv && "append".equals(inv.getExecutable().getSimpleName())) {
      PartialString left = evalBuilderChain(inv.getTarget(), ctx);
      PartialString arg = inv.getArguments().size() == 1
          ? eval(inv.getArguments().get(0), ctx.deeper()) : PartialString.unknown("?");
      return PartialString.concat(left, arg);
    }
    if (e instanceof CtConstructorCall<?> cc && isStringBuilder(cc.getType())) {
      if (!cc.getArguments().isEmpty() && !isNumeric(cc.getArguments().get(0))) {
        return eval(cc.getArguments().get(0), ctx.deeper());
      }
      return PartialString.EMPTY;
    }
    if (e instanceof CtVariableRead<?> vr && vr.getVariable().getDeclaration() instanceof CtLocalVariable<?> l
        && isStringBuilder(l.getType())) {
      return evalLocal(l, vr, ctx);
    }
    return eval(e, ctx.deeper());
  }

  private static boolean isNumeric(CtExpression<?> e) {
    CtTypeReference<?> t = Types.typeOf(e);
    return t != null && (t.getSimpleName().equals("int") || t.getSimpleName().equals("Integer"));
  }

  /** Variable locale au bout d'une chaîne d'appels ({@code sb.append(a).append(b)} → sb). */
  private static CtVariable<?> rootVariable(CtInvocation<?> inv) {
    CtExpression<?> t = inv.getTarget();
    while (t instanceof CtInvocation<?> i) {
      t = i.getTarget();
    }
    if (t instanceof CtVariableRead<?> vr && vr.getVariable() != null) {
      return vr.getVariable().getDeclaration();
    }
    return null;
  }

  /** Vrai si l'élément est sous une branche, une boucle ou un catch, à l'intérieur de scope. */
  private static boolean isConditional(CtElement element, CtElement scope) {
    for (CtElement e = element.getParent(); e != null && e != scope; e = e.isParentInitialized() ? e.getParent() : null) {
      if (e instanceof CtIf || e instanceof CtLoop || e instanceof CtSwitch<?> || e instanceof CtCase<?>
          || e instanceof CtConditional<?> || e instanceof CtCatch || e instanceof CtLambda<?>) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- appels

  private PartialString evalInvocation(CtInvocation<?> inv, Ctx ctx) {
    CtExecutableReference<?> exec = inv.getExecutable();
    String name = exec == null ? "?" : exec.getSimpleName();
    List<CtExpression<?>> args = inv.getArguments();
    CtExpression<?> target = inv.getTarget();
    String targetType = target instanceof CtTypeAccess<?> ta && ta.getAccessedType() != null
        ? ta.getAccessedType().getSimpleName()
        : (Types.typeOf(target) == null ? null : Types.typeOf(target).getSimpleName());

    // Variables d'environnement et propriétés
    if ("System".equals(targetType) && args.size() >= 1
        && (name.equals("getenv") || name.equals("getProperty"))) {
      return placeholder(args, ctx);
    }
    if ((name.equals("getProperty") || name.equals("getRequiredProperty"))
        && args.size() >= 1 && (targetType == null || ENVIRONMENTS.contains(targetType))) {
      return placeholder(args, ctx);
    }
    // Méthodes statiques de String
    if ("String".equals(targetType) && target instanceof CtTypeAccess<?>) {
      switch (name) {
        case "format":
          return args.isEmpty() ? PartialString.unknown(name)
              : format(args.get(0), args.subList(1, args.size()), ctx);
        case "valueOf", "copyValueOf":
          return args.size() == 1 ? eval(args.get(0), ctx.deeper()) : PartialString.unknown(name);
        case "join":
          return join(args, ctx);
        default:
          return PartialString.unknown(name);
      }
    }
    if (("Objects".equals(targetType) && name.equals("requireNonNull") && !args.isEmpty())
        || ("URI".equals(targetType) && name.equals("create") && args.size() == 1)
        || ("Paths".equals(targetType) && name.equals("get") && args.size() == 1)) {
      return eval(args.get(0), ctx.deeper());
    }
    // Méthodes d'instance de chaînes
    if (target != null && !(target instanceof CtTypeAccess<?>)) {
      switch (name) {
        case "formatted":
          return format(target, args, ctx);
        case "concat":
          if (args.size() == 1) {
            return PartialString.concat(eval(target, ctx.deeper()), eval(args.get(0), ctx.deeper()));
          }
          break;
        case "trimIndent", "stripIndent":
          return mapLiterals(eval(target, ctx.deeper()), ValueEval::trimIndent);
        case "trim", "strip":
          return mapLiterals(eval(target, ctx.deeper()), String::strip);
        case "toUpperCase", "uppercase":
          return mapLiterals(eval(target, ctx.deeper()), s -> s.toUpperCase(Locale.ROOT));
        case "toLowerCase", "lowercase":
          return mapLiterals(eval(target, ctx.deeper()), s -> s.toLowerCase(Locale.ROOT));
        case "append":
          if (isBuilderChain(target)) {
            return evalBuilderChain(inv, ctx);
          }
          break;
        case "path", "pathSegment":
          return joinPath(eval(target, ctx.deeper()), args, name.equals("pathSegment"), ctx);
        case "queryParam", "queryParams", "query", "replaceQuery", "replaceQueryParam",
            "fragment", "scheme", "port", "queryParamIfPresent", "encode", "build",
            "buildAndExpand", "expand", "toUri", "toUriString", "toUriTemplate", "toString",
            "intern", "normalize", "uriVariables", "queryParamDefault":
          if (isUriBuilderChain(target) || name.equals("toString") || name.equals("intern")
              || name.equals("normalize")) {
            return eval(target, ctx.deeper());
          }
          break;
        case "trimMargin":
          return mapLiterals(eval(target, ctx.deeper()), ValueEval::trimMargin);
        default:
          break;
      }
    }
    // Constructeurs d'URI
    if (target instanceof CtTypeAccess<?> && targetType != null
        && (targetType.equals("UriComponentsBuilder") || targetType.equals("UriBuilder")
        || targetType.equals("ServletUriComponentsBuilder") || targetType.equals("DefaultUriBuilderFactory"))) {
      if ((name.equals("fromHttpUrl") || name.equals("fromUriString") || name.equals("fromPath")
          || name.equals("fromUri") || name.equals("fromUrl")) && args.size() == 1) {
        return eval(args.get(0), ctx.deeper());
      }
      if (name.equals("newInstance")) {
        return PartialString.EMPTY;
      }
    }
    // Méthode du dépôt : getter ou méthode à return unique
    PartialString inlined = inline(inv, ctx);
    if (inlined != null) {
      return inlined;
    }
    return PartialString.unknown(name);
  }

  private static boolean isBuilderChain(CtExpression<?> e) {
    CtExpression<?> t = e;
    while (t instanceof CtInvocation<?> i) {
      t = i.getTarget();
    }
    if (t instanceof CtConstructorCall<?> cc) {
      return isStringBuilder(cc.getType());
    }
    return isStringBuilder(Types.typeOf(t));
  }

  private static boolean isUriBuilderChain(CtExpression<?> e) {
    CtExpression<?> t = e;
    while (t instanceof CtInvocation<?> i) {
      if (i.getTarget() instanceof CtTypeAccess<?> ta && ta.getAccessedType() != null) {
        String n = ta.getAccessedType().getSimpleName();
        return n.endsWith("UriComponentsBuilder") || n.equals("UriBuilder") || n.equals("URI");
      }
      t = i.getTarget();
    }
    CtTypeReference<?> type = Types.typeOf(t);
    return type != null && (type.getSimpleName().contains("Uri") || type.getSimpleName().equals("URI")
        || type.getSimpleName().equals("WebTarget"));
  }

  private PartialString placeholder(List<CtExpression<?>> args, Ctx ctx) {
    String key = constant(args.get(0));
    if (key == null) {
      return PartialString.unknown("property").markDynamic();
    }
    if (args.size() >= 2) {
      String def = constant(args.get(1));
      if (def != null) {
        return PartialString.lit("${" + key + ":" + def + "}");
      }
    }
    return PartialString.lit("${" + key + "}");
  }

  /** String.format / formatted : %s, %d… remplacés par les arguments, %% et %n gérés. */
  private PartialString format(CtExpression<?> fmtExpr, List<CtExpression<?>> args, Ctx ctx) {
    String fmt = constant(fmtExpr);
    if (fmt == null) {
      return PartialString.unknown("format").markDynamic();
    }
    List<CtExpression<?>> effective = args;
    if (args.size() == 1 && args.get(0) instanceof CtNewArray<?> arr) {
      effective = arr.getElements();
    }
    List<PartialString> parts = new ArrayList<>();
    Matcher m = FORMAT.matcher(fmt);
    int last = 0;
    int next = 0;
    while (m.find()) {
      parts.add(PartialString.lit(fmt.substring(last, m.start())));
      String conv = m.group(3);
      if (conv.equals("%")) {
        parts.add(PartialString.lit("%"));
      } else if (conv.equals("n")) {
        parts.add(PartialString.lit("\n"));
      } else {
        int idx = m.group(1) != null
            ? Integer.parseInt(m.group(1).substring(0, m.group(1).length() - 1)) - 1 : next++;
        parts.add(idx >= 0 && idx < effective.size()
            ? eval(effective.get(idx), ctx.deeper()) : PartialString.unknown("arg" + idx));
      }
      last = m.end();
    }
    parts.add(PartialString.lit(fmt.substring(last)));
    return PartialString.concat(parts);
  }

  private PartialString join(List<CtExpression<?>> args, Ctx ctx) {
    if (args.size() < 2) {
      return PartialString.unknown("join");
    }
    String sep = constant(args.get(0));
    if (sep == null) {
      return PartialString.unknown("join").markDynamic();
    }
    List<CtExpression<?>> items = args.subList(1, args.size());
    if (items.size() == 1) {
      CtExpression<?> only = items.get(0);
      if (only instanceof CtNewArray<?> arr) {
        items = arr.getElements();
      } else if (only instanceof CtInvocation<?> li
          && (li.getExecutable().getSimpleName().equals("of") || li.getExecutable().getSimpleName().equals("asList")
          || li.getExecutable().getSimpleName().equals("listOf"))) {
        items = li.getArguments();
      } else {
        return PartialString.unknown("join").markDynamic();
      }
    }
    List<PartialString> parts = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        parts.add(PartialString.lit(sep));
      }
      parts.add(eval(items.get(i), ctx.deeper()));
    }
    return PartialString.concat(parts);
  }

  private PartialString joinPath(PartialString base, List<CtExpression<?>> args, boolean segments, Ctx ctx) {
    PartialString out = base;
    for (CtExpression<?> a : args) {
      PartialString p = eval(a, ctx.deeper());
      String left = out.render();
      String right = p.render();
      if (right.isEmpty()) {
        continue;
      }
      boolean leftSlash = left.endsWith("/");
      boolean rightSlash = right.startsWith("/");
      if (leftSlash && rightSlash) {
        p = mapFirstLiteral(p, s -> s.substring(1));
      } else if (!leftSlash && !rightSlash && (segments || !left.isEmpty())) {
        out = PartialString.concat(out, PartialString.lit("/"));
      }
      out = PartialString.concat(out, p);
    }
    return out;
  }

  private PartialString evalConstructor(CtConstructorCall<?> cc, Ctx ctx) {
    String type = cc.getType() == null ? "" : cc.getType().getSimpleName();
    List<CtExpression<?>> args = cc.getArguments();
    switch (type) {
      case "String", "URI", "URL", "ActiveMQQueue", "ActiveMQTopic", "Queue", "Topic", "File":
        if (args.size() == 1) {
          return eval(args.get(0), ctx.deeper());
        }
        break;
      case "StringBuilder", "StringBuffer":
        return evalBuilderChain(cc, ctx);
      default:
        break;
    }
    return PartialString.unknown(type);
  }

  /** Remplace l'appel d'une méthode du dépôt par son unique expression de retour. */
  private PartialString inline(CtInvocation<?> inv, Ctx ctx) {
    CtMethod<?> method = resolveMethod(inv);
    if (method == null || method.getBody() == null || !ctx.visiting.add(method)) {
      return null;
    }
    try {
      List<CtReturn<?>> returns = method.getBody().getElements(new TypeFilter<CtReturn<?>>(CtReturn.class));
      returns.removeIf(r -> r.getParent(CtLambda.class) != null
          && r.getParent(CtLambda.class).getParent(CtMethod.class) == method);
      if (returns.size() != 1 || returns.get(0).getReturnedExpression() == null) {
        return returns.size() > 1 ? PartialString.unknown(method.getSimpleName()).markDynamic() : null;
      }
      Map<CtParameter<?>, PartialString> bindings = new IdentityHashMap<>(ctx.bindings);
      List<CtParameter<?>> params = method.getParameters();
      List<CtExpression<?>> args = inv.getArguments();
      for (int i = 0; i < params.size() && i < args.size(); i++) {
        bindings.put(params.get(i), eval(args.get(i), ctx.deeper()));
      }
      return eval(returns.get(0).getReturnedExpression(), ctx.bind(bindings));
    } finally {
      ctx.visiting.remove(method);
    }
  }

  private CtMethod<?> resolveMethod(CtInvocation<?> inv) {
    CtExecutableReference<?> ref = inv.getExecutable();
    if (ref == null) {
      return null;
    }
    try {
      if (ref.getExecutableDeclaration() instanceof CtMethod<?> m && m.getBody() != null) {
        return m;
      }
    } catch (RuntimeException ignored) {
      // déclaration introuvable en noClasspath
    }
    CtType<?> owner;
    if (inv.getTarget() == null || inv.getTarget() instanceof spoon.reflect.code.CtThisAccess<?>) {
      owner = inv.getParent(CtType.class);
    } else if (inv.getTarget() instanceof CtTypeAccess<?> ta) {
      owner = index.resolve(ta.getAccessedType());
    } else {
      owner = index.resolve(Types.typeOf(inv.getTarget()));
    }
    for (CtType<?> t = owner; t != null; t = t.getDeclaringType()) {
      CtMethod<?> found = null;
      for (CtMethod<?> m : t.getMethodsByName(ref.getSimpleName())) {
        if (m.getParameters().size() == inv.getArguments().size() && m.getBody() != null) {
          if (found != null) {
            return null;
          }
          found = m;
        }
      }
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- utilitaires

  private static PartialString mapLiterals(PartialString s, java.util.function.UnaryOperator<String> f) {
    if (s.isComplete()) {
      return new PartialString(List.of(new PartialString.Lit(f.apply(s.render()))), s.dynamic());
    }
    return s;
  }

  private static PartialString mapFirstLiteral(PartialString s, java.util.function.UnaryOperator<String> f) {
    List<PartialString.Part> parts = new ArrayList<>(s.parts());
    if (!parts.isEmpty() && parts.get(0) instanceof PartialString.Lit l) {
      parts.set(0, new PartialString.Lit(f.apply(l.text())));
    }
    return PartialString.concat(new PartialString(parts, s.dynamic()));
  }

  /** Sémantique de {@code String.trimIndent()} de Kotlin. */
  static String trimIndent(String s) {
    List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
    if (!lines.isEmpty() && lines.get(0).isBlank()) {
      lines.remove(0);
    }
    if (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
    int min = Integer.MAX_VALUE;
    for (String l : lines) {
      if (!l.isBlank()) {
        int i = 0;
        while (i < l.length() && Character.isWhitespace(l.charAt(i))) {
          i++;
        }
        min = Math.min(min, i);
      }
    }
    if (min == Integer.MAX_VALUE) {
      min = 0;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      String l = lines.get(i);
      sb.append(l.isBlank() ? "" : l.substring(min));
      if (i < lines.size() - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  /** Sémantique de {@code String.trimMargin()} de Kotlin (marge « | »). */
  static String trimMargin(String s) {
    List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
    if (!lines.isEmpty() && lines.get(0).isBlank()) {
      lines.remove(0);
    }
    if (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      String l = lines.get(i);
      String stripped = l.stripLeading();
      sb.append(stripped.startsWith("|") ? stripped.substring(1) : l);
      if (i < lines.size() - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  /** {@code baseUrl} → {@code base-url} (forme canonique Spring). */
  public static String kebab(String camel) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < camel.length(); i++) {
      char c = camel.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) {
          sb.append('-');
        }
        sb.append(Character.toLowerCase(c));
      } else if (c == '_') {
        sb.append('-');
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String nameOf(CtExpression<?> e) {
    if (e instanceof CtVariableAccess<?> va && va.getVariable() != null) {
      return va.getVariable().getSimpleName();
    }
    if (e instanceof CtAbstractInvocation<?> ai && ai.getExecutable() != null) {
      String n = ai.getExecutable().getSimpleName();
      return "<init>".equals(n) ? "new" : n;
    }
    if (e instanceof CtStatement) {
      return "?";
    }
    return "?";
  }

  /** Utilisé par les tests et extracteurs : bloc englobant d'un élément. */
  static CtBlock<?> enclosingBlock(CtElement e) {
    return e.getParent(CtBlock.class);
  }
}
