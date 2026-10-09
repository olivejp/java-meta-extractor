package fr.cafat.meta.extract;

/** Description lisible d'une exception interne : type, message, et ligne du code de l'extracteur en cause. */
public final class Failures {

  private static final String OWN_PACKAGE = "fr.cafat.meta.";

  private Failures() {
  }

  /**
   * « NullPointerException : message (à CallExtractor.java:451, CallExtractor.urlOf) », sur une ligne.
   *
   * @param e exception ; une {@code StepFailure} est remplacée par sa cause
   * @return type simple, première ligne du message si présent, et position (voir {@link #where})
   */
  public static String describe(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root && root instanceof StepFailure) {
      root = root.getCause();
    }
    String message = root.getMessage() == null ? "" : root.getMessage().strip();
    int nl = message.indexOf('\n');
    if (nl >= 0) {
      message = message.substring(0, nl).strip();
    }
    String where = where(root);
    return root.getClass().getSimpleName() + (message.isEmpty() ? "" : " : " + message)
        + (where == null ? "" : " (à " + where + ")");
  }

  /**
   * Première ligne de la pile dans le code de l'extracteur, sinon la toute première ; null si pile vide.
   *
   * @param e exception
   * @return {@code Fichier.java:ligne, Classe.méthode} ; null si pile vide
   */
  public static String where(Throwable e) {
    StackTraceElement[] stack = e.getStackTrace();
    if (stack.length == 0) {
      return null;
    }
    StackTraceElement frame = stack[0];
    for (StackTraceElement f : stack) {
      if (f.getClassName().startsWith(OWN_PACKAGE)) {
        frame = f;
        break;
      }
    }
    String cls = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1);
    return (frame.getFileName() == null ? cls : frame.getFileName()) + ":" + frame.getLineNumber() + ", "
        + cls + "." + frame.getMethodName();
  }

  /** Échec d'une étape qui empêche toute la suite (lecture du dépôt, de la configuration). */
  public static final class StepFailure extends RuntimeException {

    private final String step;

    /**
     * Échec de l'étape {@code step}.
     *
     * @param step nom lisible de l'étape (ex. « lecture du code Java et Kotlin »)
     * @param cause exception d'origine
     */
    public StepFailure(String step, Throwable cause) {
      super(step + " : " + describe(cause), cause);
      this.step = step;
    }

    /**
     * Nom lisible de l'étape en échec.
     *
     * @return nom donné à la construction
     */
    public String step() {
      return step;
    }
  }
}
