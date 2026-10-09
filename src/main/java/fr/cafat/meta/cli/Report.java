package fr.cafat.meta.cli;

import fr.cafat.meta.extract.DiagnosticCatalog;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.Source;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rapport lisible des diagnostics d'une application, écrit sur stderr après la ligne de résumé.
 * Les diagnostics sont groupés par niveau puis par code ; chaque groupe rappelle où chercher la
 * cause, ce qui manque dans le résultat et ce qu'il faut faire ({@link DiagnosticCatalog}), puis
 * liste ses occurrences : toutes pour les erreurs, {@code maxWarnings} pour les avertissements,
 * une pour les infos. Le JSON garde toujours la liste complète.
 */
final class Report {

  private static final List<String> LEVELS = List.of(Diagnostics.ERROR, Diagnostics.WARNING, Diagnostics.INFO);
  private static final Map<String, String> LABELS = Map.of(Diagnostics.ERROR, "ERREUR",
      Diagnostics.WARNING, "AVERTISSEMENT", Diagnostics.INFO, "INFO");
  private static final String INDENT = "    ";

  private Report() {
  }

  static void print(PrintWriter err, List<Diagnostic> diagnostics, Path target, int maxWarnings) {
    for (String level : LEVELS) {
      Map<String, List<Diagnostic>> byCode = new LinkedHashMap<>();
      diagnostics.stream().filter(d -> level.equals(d.level()))
          .forEach(d -> byCode.computeIfAbsent(d.code(), k -> new ArrayList<>()).add(d));
      List<Map.Entry<String, List<Diagnostic>>> groups = new ArrayList<>(byCode.entrySet());
      groups.sort(Comparator.<Map.Entry<String, List<Diagnostic>>>comparingInt(e -> -e.getValue().size())
          .thenComparing(Map.Entry::getKey));
      int max = switch (level) {
        case Diagnostics.ERROR -> Integer.MAX_VALUE;
        case Diagnostics.WARNING -> maxWarnings;
        default -> 1;
      };
      for (Map.Entry<String, List<Diagnostic>> g : groups) {
        group(err, LABELS.get(level), g.getKey(), g.getValue(), max, target);
      }
    }
  }

  private static void group(PrintWriter err, String label, String code, List<Diagnostic> items, int max,
      Path target) {
    DiagnosticCatalog.Entry e = DiagnosticCatalog.of(code);
    int n = items.size();
    err.println();
    err.println("  " + label + " · " + code + " · " + e.title() + " · " + n + " occurrence" + (n > 1 ? "s" : ""));
    err.println(INDENT + "origine : " + e.origin().label);
    err.println(INDENT + "manque  : " + e.impact());
    err.println(INDENT + "à faire : " + e.action());
    items.stream().limit(max).forEach(d -> err.println(INDENT + "- " + where(d.source()) + d.message()));
    if (n > max) {
      err.println(INDENT + "… et " + (n - max) + " autre" + (n - max > 1 ? "s" : "")
          + " : diagnostics de code " + code + " dans " + target);
    }
  }

  /** « fichier:ligne — », sinon « classe — », sinon rien. */
  static String where(Source s) {
    if (s == null) {
      return "";
    }
    if (s.file() != null) {
      return s.file() + (s.line() == null ? "" : ":" + s.line()) + " — ";
    }
    return s.className() == null ? "" : s.className() + " — ";
  }

  /** Échec qui empêche toute sortie pour un dépôt. */
  static void fatal(PrintWriter err, String repo, String step, String error) {
    err.println(repo + " : ÉCHEC DE L'EXTRACTION, aucun fichier écrit pour ce dépôt");
    if (step != null) {
      err.println(INDENT + "étape   : " + step);
    }
    err.println(INDENT + "erreur  : " + error);
    err.println(INDENT + "à faire : erreur interne de l'extracteur ; corriger la classe et la ligne citées "
        + "(« à … »), relancer avec --stacktrace pour la pile complète");
  }

  /** Liste des codes documentés (--list-diagnostics). */
  static void catalog(PrintWriter out) {
    DiagnosticCatalog.all().forEach((code, e) -> {
      out.println(code + " · " + e.title());
      out.println(INDENT + "origine : " + e.origin().label);
      out.println(INDENT + "manque  : " + e.impact());
      out.println(INDENT + "à faire : " + e.action());
    });
  }
}
