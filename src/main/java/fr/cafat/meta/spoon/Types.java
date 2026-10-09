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
   *
   * @param ref référence de type ; null accepté
   * @return rendu ({@code List<String>}, {@code int[]}, {@code ? extends Foo}) ; null si {@code ref} est null
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

  /**
   * Nom de base sans génériques (java.lang.* en simple).
   *
   * @param ref référence de type ; obligatoire
   * @return nom simple pour un primitif ou un type de {@code java.lang}, sinon nom qualifié
   */
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

  /**
   * Nom simple.
   *
   * @param ref référence de type ; null accepté
   * @return nom simple, ou null si {@code ref} est null
   */
  public static String simpleName(CtTypeReference<?> ref) {
    return ref == null ? null : ref.getSimpleName();
  }

  /**
   * Vrai si le nom simple est l'un de ceux donnés.
   *
   * @param ref référence de type ; null accepté
   * @param simpleNames noms simples acceptés, sensibles à la casse
   * @return true si correspondance ; false si {@code ref} est null
   */
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

  /**
   * Type statique d'une expression, sans exception.
   *
   * @param e expression ; null accepté
   * @return type calculé par Spoon, ou null si inconnu, en erreur ou {@code e} null
   */
  public static CtTypeReference<?> typeOf(CtExpression<?> e) {
    try {
      return e == null ? null : e.getType();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /**
   * Vrai pour un tableau, une collection ou une Map connue.
   *
   * @param ref référence de type ; null accepté
   * @return true pour un tableau ou un nom simple de collection connu (List, Set, Optional, Flux…)
   */
  public static boolean isCollection(CtTypeReference<?> ref) {
    return ref != null && (ref instanceof CtArrayTypeReference<?>
        || COLLECTIONS.contains(ref.getSimpleName()) || MAPS.contains(ref.getSimpleName()));
  }

  /**
   * Type des éléments d'une collection, d'un tableau ou valeur d'une Map ; sinon le type lui-même.
   *
   * @param ref référence de type ; null accepté
   * @return type d'élément ; null si argument générique absent ou {@code ref} null
   */
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

  /**
   * Retire les enveloppes HTTP/asynchrones (ResponseEntity&lt;T&gt;, Mono&lt;T&gt;…).
   *
   * @param ref type de retour ; null accepté
   * @return type utile ; null si enveloppe sans argument, avec joker, ou {@code ref} null
   */
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

  /**
   * Vrai pour {@code void}, {@code Void}, {@code Unit} ou une référence null.
   *
   * @param ref référence de type ; null accepté
   * @return true si le type ne porte aucune valeur
   */
  public static boolean isVoid(CtTypeReference<?> ref) {
    return ref == null || "void".equals(ref.getSimpleName()) || "Void".equals(ref.getSimpleName())
        || "Unit".equals(ref.getSimpleName());
  }
}
