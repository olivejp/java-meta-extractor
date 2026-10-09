package fr.cafat.meta.extract;

import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.Source;
import java.util.ArrayList;
import java.util.List;

/**
 * Collecteur des diagnostics d'une extraction. Les id sont calculés à l'assemblage final.
 *
 * <p>Les codes sont stables ; chacun est expliqué dans {@link DiagnosticCatalog}, qu'il faut
 * compléter pour tout nouveau code. Les exceptions internes isolées par étape sont gardées à part
 * (hors JSON), pour afficher leur pile avec {@code --stacktrace}.
 */
public final class Diagnostics {

  public static final String ERROR = "error";
  public static final String WARNING = "warning";
  public static final String INFO = "info";

  private final List<Diagnostic> items = new ArrayList<>();
  private final List<Throwable> failures = new ArrayList<>();

  public void error(String code, String message, Source source) {
    add(ERROR, code, message, source);
  }

  public void warning(String code, String message, Source source) {
    add(WARNING, code, message, source);
  }

  public void info(String code, String message, Source source) {
    add(INFO, code, message, source);
  }

  public synchronized void add(String level, String code, String message, Source source) {
    items.add(new Diagnostic(null, level, code, message, source));
  }

  public synchronized List<Diagnostic> all() {
    return List.copyOf(items);
  }

  /** Échec interne d'une étape : diagnostic {@code error} lisible, exception gardée pour sa pile. */
  public synchronized void failure(String step, Throwable e) {
    add(ERROR, DiagnosticCatalog.STEP_FAILED, step + " : " + Failures.describe(e), null);
    failures.add(e);
  }

  public synchronized List<Throwable> failures() {
    return List.copyOf(failures);
  }
}
