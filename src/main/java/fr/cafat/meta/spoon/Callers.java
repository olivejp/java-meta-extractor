package fr.cafat.meta.spoon;

import spoon.reflect.code.CtLambda;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;

/** Identification de l'appelant d'un élément de code. */
public final class Callers {

  private Callers() {
  }

  /**
   * Méthode ou constructeur englobant, lambdas traversées : {@code fqn#methode} ou {@code fqn#<init>} ;
   * null hors de toute méthode (initialiseur de champ, annotation).
   */
  public static String of(CtElement e) {
    CtExecutable<?> exec = e instanceof CtExecutable<?> self && !(e instanceof CtLambda<?>)
        ? self : e.getParent(CtExecutable.class);
    while (exec instanceof CtLambda<?>) {
      exec = exec.getParent(CtExecutable.class);
    }
    if (exec instanceof CtMethod<?> m && m.getDeclaringType() != null) {
      return m.getDeclaringType().getQualifiedName() + "#" + m.getSimpleName();
    }
    if (exec instanceof CtConstructor<?> c && c.getDeclaringType() != null) {
      return c.getDeclaringType().getQualifiedName() + "#<init>";
    }
    return null;
  }
}
