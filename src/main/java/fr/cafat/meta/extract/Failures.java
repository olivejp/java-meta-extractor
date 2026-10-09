package fr.cafat.meta.extract;

/** Description lisible d'une exception interne : type, message, et ligne du code de l'extracteur en cause. */
public final class Failures {

  private static final String OWN_PACKAGE = "fr.cafat.meta.";

  private Failures() {
  }

  /** « NullPointerException : message (à CallExtractor.java:451, CallExtractor.urlOf) », sur une ligne. */
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

  /** Première ligne de la pile dans le code de l'extracteur, sinon la toute première ; null si pile vide. */
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

    public StepFailure(String step, Throwable cause) {
      super(step + " : " + describe(cause), cause);
      this.step = step;
    }

    public String step() {
      return step;
    }
  }
}
