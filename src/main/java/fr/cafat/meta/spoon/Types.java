package fr.cafat.meta.spoon;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import spoon.reflect.code.CtExpression;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;

/** Rendu et manipulation des références de types. */
public final class Types {

  private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Collection", "Iterable",
      "SortedSet", "NavigableSet", "LinkedList", "ArrayList", "HashSet", "TreeSet", "LinkedHashSet",
      "MutableList", "MutableSet", "MutableCollection", "Stream", "Flux", "Optional", "Mono");
  private static final Set<String> MAPS = Set.of("Map", "SortedMap", "HashMap", "TreeMap",
      "LinkedHashMap", "MutableMap");
  private static final Set<String> WRAPPERS = Set.of("ResponseEntity", "HttpEntity", "Mono",
      "CompletableFuture", "CompletionStage", "Callable", "DeferredResult", "Future",
      "ListenableFuture", "WebAsyncTask");

  private Types() {
  }

  /**
   * Rendu d'un type : java.lang.* et types primitifs en nom simple, autres types en nom qualifié,
   * arguments génériques conservés.
   */
  public static String render(CtTypeReference<?> ref) {
    if (ref == null) {
      return null;
    }
    if (ref instanceof CtArrayTypeReference<?> arr) {
      return render(arr.getComponentType()) + "[]";
    }
    if (ref instanceof CtWildcardReference w) {
      return w.getBoundingType() == null || w.isDefaultBoundingType() ? "?" : "? "
          + (w.isUpper() ? "extends " : "super ") + render(w.getBoundingType());
    }
    String name = baseName(ref);
    List<CtTypeReference<?>> args = ref.getActualTypeArguments();
    if (args == null || args.isEmpty()) {
      return name;
    }
    return name + "<" + args.stream().map(Types::render).collect(Collectors.joining(", ")) + ">";
  }

  /** Nom de base sans génériques (java.lang.* en simple). */
  public static String baseName(CtTypeReference<?> ref) {
    if (ref.isPrimitive()) {
      return ref.getSimpleName();
    }
    String qn = ref.getQualifiedName();
    if (qn.startsWith("java.lang.") && qn.indexOf('.', "java.lang.".length()) < 0) {
      return ref.getSimpleName();
    }
    return qn;
  }

  public static String simpleName(CtTypeReference<?> ref) {
    return ref == null ? null : ref.getSimpleName();
  }

  public static boolean isNamed(CtTypeReference<?> ref, String... simpleNames) {
    if (ref == null) {
      return false;
    }
    String sn = ref.getSimpleName();
    for (String s : simpleNames) {
      if (s.equals(sn)) {
        return true;
      }
    }
    return false;
  }

  /** Type statique d'une expression, sans exception. */
  public static CtTypeReference<?> typeOf(CtExpression<?> e) {
    try {
      return e == null ? null : e.getType();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  public static boolean isCollection(CtTypeReference<?> ref) {
    return ref != null && (ref instanceof CtArrayTypeReference<?>
        || COLLECTIONS.contains(ref.getSimpleName()) || MAPS.contains(ref.getSimpleName()));
  }

  /** Type des éléments d'une collection, d'un tableau ou valeur d'une Map ; sinon le type lui-même. */
  public static CtTypeReference<?> elementType(CtTypeReference<?> ref) {
    if (ref == null) {
      return null;
    }
    if (ref instanceof CtArrayTypeReference<?> arr) {
      return arr.getComponentType();
    }
    List<CtTypeReference<?>> args = ref.getActualTypeArguments();
    if (MAPS.contains(ref.getSimpleName())) {
      return args.size() == 2 ? args.get(1) : null;
    }
    if (COLLECTIONS.contains(ref.getSimpleName())) {
      return args.size() == 1 ? args.get(0) : null;
    }
    return ref;
  }

  /** Retire les enveloppes HTTP/asynchrones (ResponseEntity&lt;T&gt;, Mono&lt;T&gt;…). */
  public static CtTypeReference<?> unwrapResponse(CtTypeReference<?> ref) {
    CtTypeReference<?> cur = ref;
    while (cur != null && WRAPPERS.contains(cur.getSimpleName())) {
      List<CtTypeReference<?>> args = cur.getActualTypeArguments();
      if (args.size() != 1 || args.get(0) instanceof CtWildcardReference) {
        return null;
      }
      cur = args.get(0);
    }
    return cur;
  }

  public static boolean isVoid(CtTypeReference<?> ref) {
    return ref == null || "void".equals(ref.getSimpleName()) || "Void".equals(ref.getSimpleName())
        || "Unit".equals(ref.getSimpleName());
  }
}
