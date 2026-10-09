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

  /**
   * Ajoute un diagnostic {@code error}.
   *
   * @param code code stable, documenté dans {@link DiagnosticCatalog}
   * @param message description lisible de l'occurrence
   * @param source provenance (fichier, ligne) ; null si aucune
   */
  public void error(String code, String message, Source source) {
    add(ERROR, code, message, source);
  }

  /**
   * Ajoute un diagnostic {@code warning}.
   *
   * @param code code stable, documenté dans {@link DiagnosticCatalog}
   * @param message description lisible de l'occurrence
   * @param source provenance (fichier, ligne) ; null si aucune
   */
  public void warning(String code, String message, Source source) {
    add(WARNING, code, message, source);
  }

  /**
   * Ajoute un diagnostic {@code info}.
   *
   * @param code code stable, documenté dans {@link DiagnosticCatalog}
   * @param message description lisible de l'occurrence
   * @param source provenance (fichier, ligne) ; null si aucune
   */
  public void info(String code, String message, Source source) {
    add(INFO, code, message, source);
  }

  /**
   * Ajoute un diagnostic du niveau donné. Thread-safe.
   *
   * @param level {@link #ERROR}, {@link #WARNING} ou {@link #INFO}
   * @param code code stable, documenté dans {@link DiagnosticCatalog}
   * @param message description lisible de l'occurrence
   * @param source provenance (fichier, ligne) ; null si aucune
   */
  public synchronized void add(String level, String code, String message, Source source) {
    items.add(new Diagnostic(null, level, code, message, source));
  }

  /**
   * Copie des diagnostics, dans l'ordre d'ajout.
   *
   * @return liste non modifiable, id à null (calculés par {@code Assembler})
   */
  public synchronized List<Diagnostic> all() {
    return List.copyOf(items);
  }

  /**
   * Échec interne d'une étape : diagnostic {@code error} lisible, exception gardée pour sa pile.
   *
   * @param step nom lisible de l'étape (ex. « appels REST sortants »)
   * @param e exception levée par l'étape
   */
  public synchronized void failure(String step, Throwable e) {
    add(ERROR, DiagnosticCatalog.STEP_FAILED, step + " : " + Failures.describe(e), null);
    failures.add(e);
  }

  /**
   * Exceptions des étapes en échec, dans l'ordre d'ajout.
   *
   * @return liste non modifiable ; vide si aucun échec
   */
  public synchronized List<Throwable> failures() {
    return List.copyOf(failures);
  }
}
