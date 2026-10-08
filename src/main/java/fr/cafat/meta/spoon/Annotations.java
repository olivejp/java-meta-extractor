package fr.cafat.meta.spoon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

/**
 * Lecture des annotations en noClasspath. Avec un import étoile, Spoon attribue à l'annotation le
 * paquet du fichier courant : le rapprochement se fait donc par nom simple, puis on écarte les
 * annotations dont le paquet est connu et ne fait pas partie des paquets attendus.
 */
public final class Annotations {

  public static final Set<String> JPA = Set.of("javax.persistence", "jakarta.persistence");
  public static final Set<String> SPRING_WEB = Set.of("org.springframework.web.bind.annotation");
  public static final Set<String> JAXRS = Set.of("javax.ws.rs", "jakarta.ws.rs");
  public static final Set<String> ANY = Set.of();

  private Annotations() {
  }

  /** Première annotation de nom simple {@code simpleName} acceptable pour les paquets donnés. */
  public static CtAnnotation<?> find(CtElement element, Set<String> packages, String simpleName) {
    List<CtAnnotation<?>> all = findAll(element, packages, simpleName);
    return all.isEmpty() ? null : all.get(0);
  }

  /** Comme {@link #find} pour plusieurs noms : première trouvée dans l'ordre des noms. */
  public static CtAnnotation<?> findAny(CtElement element, Set<String> packages,
      String... simpleNames) {
    for (String n : simpleNames) {
      CtAnnotation<?> a = find(element, packages, n);
      if (a != null) {
        return a;
      }
    }
    return null;
  }

  public static boolean has(CtElement element, Set<String> packages, String simpleName) {
    return find(element, packages, simpleName) != null;
  }

  /**
   * Annotations correspondantes, celles d'un paquet attendu en premier, puis dans l'ordre du
   * source. Les annotations répétées dans un conteneur (ex. {@code @JoinColumns}) ne sont pas
   * dépliées ici : voir {@link #nested}.
   */
  public static List<CtAnnotation<?>> findAll(CtElement element, Set<String> packages,
      String simpleName) {
    List<CtAnnotation<?>> exact = new ArrayList<>();
    List<CtAnnotation<?>> fallback = new ArrayList<>();
    if (element == null) {
      return exact;
    }
    for (CtAnnotation<?> a : element.getAnnotations()) {
      CtTypeReference<?> t = a.getAnnotationType();
      if (t == null || !simpleName.equals(t.getSimpleName())) {
        continue;
      }
      String pkg = packageOf(t);
      if (packages.isEmpty() || packages.contains(pkg)) {
        exact.add(a);
      } else if (isUnresolvedPackage(element, pkg)) {
        fallback.add(a);
      }
    }
    exact.addAll(fallback);
    return exact;
  }

  /**
   * Vrai si le paquet attribué par Spoon ne dit rien du vrai paquet : vide, ou paquet du fichier
   * courant (import étoile non résolu).
   */
  private static boolean isUnresolvedPackage(CtElement element, String pkg) {
    if (pkg.isEmpty()) {
      return true;
    }
    CtType<?> type = element instanceof CtType<?> t ? t : element.getParent(CtType.class);
    while (type != null && type.getDeclaringType() != null) {
      type = type.getDeclaringType();
    }
    if (type == null) {
      return false;
    }
    String own = type.getPackage() == null ? "" : type.getPackage().getQualifiedName();
    return pkg.equals(own);
  }

  private static String packageOf(CtTypeReference<?> t) {
    String qn = t.getQualifiedName();
    int i = qn.lastIndexOf('.');
    return i < 0 ? "" : qn.substring(0, i);
  }

  /** Expression déclarée pour un attribut (sans valeur par défaut), ou null. */
  public static CtExpression<?> value(CtAnnotation<?> a, String key) {
    if (a == null) {
      return null;
    }
    CtExpression<?> e = a.getValues().get(key);
    return e;
  }

  /** Première expression déclarée parmi plusieurs alias d'attribut ({@code value}, {@code path}…). */
  public static CtExpression<?> firstValue(CtAnnotation<?> a, String... keys) {
    for (String k : keys) {
      CtExpression<?> e = value(a, k);
      if (e != null && !isEmptyArray(e)) {
        return e;
      }
    }
    return null;
  }

  /** Éléments d'un attribut (tableau déplié ; valeur unique en liste d'un élément). */
  public static List<CtExpression<?>> values(CtAnnotation<?> a, String key) {
    return flatten(value(a, key));
  }

  public static List<CtExpression<?>> flatten(CtExpression<?> e) {
    List<CtExpression<?>> out = new ArrayList<>();
    if (e == null) {
      return out;
    }
    if (e instanceof CtNewArray<?> arr) {
      for (CtExpression<?> el : arr.getElements()) {
        out.addAll(flatten(el));
      }
    } else {
      out.add(e);
    }
    return out;
  }

  private static boolean isEmptyArray(CtExpression<?> e) {
    return e instanceof CtNewArray<?> arr && arr.getElements().isEmpty();
  }

  /** Annotations imbriquées d'un attribut ({@code @JoinTable(joinColumns = {@JoinColumn…})}). */
  public static List<CtAnnotation<?>> nested(CtAnnotation<?> a, String key) {
    List<CtAnnotation<?>> out = new ArrayList<>();
    for (CtExpression<?> e : values(a, key)) {
      if (e instanceof CtAnnotation<?> n) {
        out.add(n);
      }
    }
    return out;
  }

  /** Nom de la constante d'énumération ({@code InheritanceType.JOINED} → {@code JOINED}). */
  public static String enumConstant(CtExpression<?> e) {
    if (e instanceof CtFieldRead<?> fr && fr.getVariable() != null) {
      String name = fr.getVariable().getSimpleName();
      return "class".equals(name) ? null : name;
    }
    return null;
  }

  /** Type d'un littéral de classe ({@code Foo.class}, {@code Foo::class}), sinon null. */
  public static CtTypeReference<?> classLiteral(CtExpression<?> e) {
    if (e instanceof CtFieldRead<?> fr && fr.getVariable() != null
        && "class".equals(fr.getVariable().getSimpleName())) {
      if (fr.getTarget() instanceof CtTypeAccess<?> ta) {
        return ta.getAccessedType();
      }
      return fr.getVariable().getDeclaringType();
    }
    return null;
  }

  /** Booléen littéral, ou null si absent ou non littéral. */
  public static Boolean bool(CtAnnotation<?> a, String key) {
    CtExpression<?> e = value(a, key);
    if (e instanceof spoon.reflect.code.CtLiteral<?> lit && lit.getValue() instanceof Boolean b) {
      return b;
    }
    return null;
  }

  /** Entier littéral, ou null si absent ou non littéral. */
  public static Integer integer(CtAnnotation<?> a, String key) {
    CtExpression<?> e = value(a, key);
    if (e instanceof spoon.reflect.code.CtLiteral<?> lit && lit.getValue() instanceof Number n) {
      return n.intValue();
    }
    return null;
  }

  /**
   * Annotation présente directement ou via une méta-annotation déclarée dans le dépôt (ex.
   * annotation maison portant {@code @RestController}).
   */
  public static boolean hasMeta(CtElement element, TypeIndex index, Set<String> packages,
      String simpleName) {
    return hasMeta(element, index, packages, simpleName, 0);
  }

  private static boolean hasMeta(CtElement element, TypeIndex index, Set<String> packages,
      String simpleName, int depth) {
    if (has(element, packages, simpleName)) {
      return true;
    }
    if (depth > 4 || index == null) {
      return false;
    }
    for (CtAnnotation<?> a : element.getAnnotations()) {
      CtType<?> decl = index.resolve(a.getAnnotationType());
      if (decl != null && decl != element
          && hasMeta(decl, index, packages, simpleName, depth + 1)) {
        return true;
      }
    }
    return false;
  }
}
