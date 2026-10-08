package fr.cafat.meta.extract;

import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.Source;
import java.util.ArrayList;
import java.util.List;

/**
 * Collecteur des diagnostics d'une extraction. Les id sont calculés à l'assemblage final.
 *
 * <p>Codes stables : URL_UNRESOLVED, SQL_UNPARSED, DATASOURCE_AMBIGUOUS, PARENT_NOT_FOUND,
 * ENTITY_UNREFERENCED, ENTITY_NOT_FOUND, ENUM_NOT_FOUND, NAMING_STRATEGY_UNKNOWN,
 * DESTINATION_UNRESOLVED, PARSE_ERROR, KOTLIN_UNSUPPORTED, CONTROLLER_INTERFACE_NOT_FOUND,
 * ENDPOINT_DUPLICATE, CONTEXT_ROOT_UNKNOWN, PROFILE_NOT_APPLIED, NO_DEPLOYABLE_MODULE,
 * CONFIG_PARSE_ERROR, HTTP_METHOD_UNRESOLVED, TARGET_APP_UNKNOWN, APP_NAME_COLLISION.
 */
public final class Diagnostics {

  public static final String ERROR = "error";
  public static final String WARNING = "warning";
  public static final String INFO = "info";

  private final List<Diagnostic> items = new ArrayList<>();

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
}
