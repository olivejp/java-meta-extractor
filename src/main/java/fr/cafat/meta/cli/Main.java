package fr.cafat.meta.cli;

import com.fasterxml.jackson.databind.JsonNode;
import fr.cafat.meta.config.CloudConfigRepo;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.extract.Failures;
import fr.cafat.meta.extract.Failures.StepFailure;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.ExtractionResult;
import fr.cafat.meta.output.CanonicalJson;
import fr.cafat.meta.output.SchemaValidator;
import fr.cafat.meta.parse.kotlin.KotlinToSpoon;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * Point d'entrée. Codes de sortie : 0 succès ; 1 au moins un diagnostic {@code error} (ou
 * {@code warning} avec {@code --fail-on-warning}) ; 2 sortie non conforme au schéma ; 3 erreur
 * d'usage, d'entrée-sortie ou exception interne. Le code le plus élevé l'emporte.
 */
@Command(name = "java-meta-extractor", mixinStandardHelpOptions = true, versionProvider = Main.Version.class,
    sortOptions = false, exitCodeOnInvalidInput = Main.EXIT_USAGE, exitCodeOnExecutionException = Main.EXIT_USAGE,
    description = "Extrait de façon déterministe les métadonnées d'un dépôt Java/Kotlin dans out/<application>.json.")
public final class Main implements Callable<Integer> {

  static final int EXIT_OK = 0;
  static final int EXIT_DIAGNOSTIC = 1;
  static final int EXIT_SCHEMA = 2;
  static final int EXIT_USAGE = 3;
  private static final int MAX_SCHEMA_ERRORS = 20;

  /** Cible exclusive : un dépôt ({@code --repo}) ou un répertoire de dépôts ({@code --repos-dir}). */
  static final class Target {
    @Option(names = "--repo", paramLabel = "DIR", description = "Dépôt à analyser.")
    Path repo;

    @Option(names = "--repos-dir", paramLabel = "DIR",
        description = "Répertoire dont chaque sous-répertoire contenant un build Maven/Gradle ou un .git est un dépôt.")
    Path reposDir;
  }

  @ArgGroup(exclusive = true, multiplicity = "1")
  Target target;

  @Option(names = "--commit", paramLabel = "SHA",
      description = "Commit inscrit dans la sortie (défaut : HEAD lu dans .git). Avec --repo seulement.")
  String commit;

  @Option(names = "--out", required = true, paramLabel = "DIR", description = "Répertoire de sortie.")
  Path out;

  @Option(names = "--app-name", paramLabel = "NOM",
      description = "Nom imposé de l'application (dépôt à une seule unité déployable). Avec --repo seulement.")
  String appName;

  @Option(names = "--profile", split = ",", paramLabel = "PROFIL", description = "Profil(s) Spring actif(s).")
  List<String> profiles = new ArrayList<>();

  @Option(names = "--config-repo", paramLabel = "DIR",
      description = "Clone local du dépôt Spring Cloud Config, appliqué aux applications clientes.")
  Path configRepo;

  @Option(names = "--config-search-paths", split = ",", paramLabel = "CHEMIN",
      description = "Répertoires de recherche du dépôt de configuration (search-paths du serveur ; "
          + "{application}, {profile} et * acceptés). Défaut : racine seule.")
  List<String> configSearchPaths = new ArrayList<>();

  @Option(names = "--fail-on-warning", description = "Code 1 aussi en présence d'un diagnostic warning.")
  boolean failOnWarning;

  @Option(names = "--view-schemas", split = ",", paramLabel = "SCHEMA", defaultValue = "MGENGPP",
      description = "Schémas dont toutes les tables sont des vues (défaut : ${DEFAULT-VALUE}).")
  List<String> viewSchemas;

  @Option(names = "--max-warnings", paramLabel = "N", defaultValue = "5",
      description = "Occurrences affichées par code d'avertissement dans le rapport (défaut : ${DEFAULT-VALUE}).")
  int maxWarnings;

  @Option(names = "--stacktrace", description = "Affiche la pile complète des erreurs internes.")
  boolean stacktrace;

  @SuppressWarnings("deprecation")
  @Option(names = "--list-diagnostics", help = true,
      description = "Liste les codes de diagnostic, avec leur origine et ce qu'il faut faire.")
  boolean listDiagnostics;

  @Spec
  CommandSpec spec;

  /** Traduction des sources Kotlin dans le modèle Spoon. */
  static final Pipeline.KotlinFactory KOTLIN =
      (root, diagnostics) -> (factory, files) -> new KotlinToSpoon(factory, root, diagnostics).translate(files);

  /**
   * Lance la commande puis termine la JVM avec le code de sortie.
   *
   * @param args arguments de la ligne de commande (voir {@code --help})
   */
  public static void main(String[] args) {
    System.exit(execute(args));
  }

  static int execute(String... args) {
    return command().execute(args);
  }

  /** Exécution avec une sortie d'erreur fournie (tests). */
  static int execute(PrintWriter err, String... args) {
    return command().setErr(err).setOut(err).execute(args);
  }

  /** Une exception non rattrapée (entrée-sortie) donne un message d'une ligne, pas une pile Java. */
  private static CommandLine command() {
    return new CommandLine(new Main()).setExecutionExceptionHandler((e, cmd, parsed) -> {
      cmd.getErr().println("java-meta-extractor : ÉCHEC : " + Failures.describe(e));
      if (((Main) cmd.getCommand()).stacktrace) {
        e.printStackTrace(cmd.getErr());
      }
      return EXIT_USAGE;
    });
  }

  @Override
  public Integer call() throws IOException {
    PrintWriter err = spec.commandLine().getErr();
    if (listDiagnostics) {
      Report.catalog(spec.commandLine().getOut());
      spec.commandLine().getOut().flush();
      return EXIT_OK;
    }
    List<Path> repos = repos();
    Files.createDirectories(out);
    if (configRepo != null && !Files.isDirectory(configRepo)) {
      throw new ParameterException(spec.commandLine(), "--config-repo : répertoire introuvable : " + configRepo);
    }
    CloudConfigRepo cloudConfig = configRepo == null ? null : new CloudConfigRepo(configRepo, configSearchPaths);
    Pipeline.Options options = new Pipeline.Options(commit, appName, List.copyOf(profiles),
        Set.copyOf(viewSchemas), KOTLIN, cloudConfig);
    int exit = EXIT_OK;
    Set<String> written = new HashSet<>();
    for (Path repo : repos) {
      List<Pipeline.Output> outputs;
      try {
        outputs = Pipeline.run(repo, options);
      } catch (RuntimeException | StackOverflowError e) {
        Throwable cause = e instanceof StepFailure f ? f.getCause() : e;
        Report.fatal(err, repo.getFileName().toString(), e instanceof StepFailure f ? f.step() : null,
            Failures.describe(cause));
        if (stacktrace) {
          cause.printStackTrace(err);
        }
        exit = Math.max(exit, EXIT_USAGE);
        continue;
      }
      for (Pipeline.Output o : outputs) {
        String file = fileName(o.name());
        if (!written.add(file)) {
          String alt = fileName(o.name() + "~" + repo.getFileName());
          err.println(o.name() + " : nom déjà produit par un autre dépôt, écrit dans " + alt + ".json");
          file = alt;
          written.add(file);
        }
        exit = Math.max(exit, write(o, file, err));
      }
    }
    err.flush();
    return exit;
  }

  private List<Path> repos() throws IOException {
    if (target.repo != null) {
      if (!Files.isDirectory(target.repo)) {
        throw new ParameterException(spec.commandLine(), "--repo : répertoire introuvable : " + target.repo);
      }
      return List.of(target.repo);
    }
    if (commit != null || appName != null) {
      throw new ParameterException(spec.commandLine(), "--commit et --app-name ne s'emploient qu'avec --repo");
    }
    if (!Files.isDirectory(target.reposDir)) {
      throw new ParameterException(spec.commandLine(), "--repos-dir : répertoire introuvable : " + target.reposDir);
    }
    try (Stream<Path> s = Files.list(target.reposDir)) {
      List<Path> repos = s.filter(Files::isDirectory).filter(Main::isRepo)
          .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString())).toList();
      if (repos.isEmpty()) {
        throw new ParameterException(spec.commandLine(), "--repos-dir : aucun dépôt dans " + target.reposDir);
      }
      return repos;
    }
  }

  static boolean isRepo(Path dir) {
    for (String marker : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "settings.gradle.kts", ".git")) {
      if (Files.exists(dir.resolve(marker))) {
        return true;
      }
    }
    return false;
  }

  /** Valide, écrit et résume un résultat ; renvoie son code de sortie. */
  private int write(Pipeline.Output output, String file, PrintWriter err) throws IOException {
    ExtractionResult result = output.result();
    JsonNode tree = CanonicalJson.toTree(result);
    List<String> errors = SchemaValidator.validate(tree);
    int code = EXIT_OK;
    Path target;
    if (errors.isEmpty()) {
      target = out.resolve(file + ".json");
    } else {
      target = out.resolve(file + ".json.invalid");
      code = EXIT_SCHEMA;
    }
    Files.write(target, CanonicalJson.write(tree));
    long nErrors = count(result, Diagnostics.ERROR);
    long nWarnings = count(result, Diagnostics.WARNING);
    if (nErrors > 0 || (failOnWarning && nWarnings > 0)) {
      code = Math.max(code, EXIT_DIAGNOSTIC);
    }
    err.println(summary(result, target, nErrors, nWarnings));
    if (!errors.isEmpty()) {
      err.println();
      err.println("  ERREUR · sortie non conforme au schéma · " + errors.size() + " écart(s), fichier écrit en " + target);
      err.println("    origine : limite ou erreur de l'extracteur (le JSON produit ne respecte pas schema/)");
      err.println("    à faire : corriger l'extracteur ou le schéma pour les chemins cités");
      errors.stream().limit(MAX_SCHEMA_ERRORS).forEach(e -> err.println("    - " + e));
    }
    Report.print(err, result.diagnostics(), target, maxWarnings);
    if (stacktrace) {
      output.failures().forEach(f -> f.printStackTrace(err));
    }
    if (!result.diagnostics().isEmpty() || !errors.isEmpty()) {
      err.println();
    }
    return code;
  }

  static String summary(ExtractionResult r, Path target, long errors, long warnings) {
    return r.application().id() + " : " + r.entities().size() + " entités, " + r.relations().size()
        + " relations, " + r.sqlAccesses().size() + " accès SQL, " + r.endpoints().size() + " endpoints, "
        + r.calls().size() + " appels, " + r.messaging().size() + " échanges JMS, " + r.diagnostics().size()
        + " diagnostics (" + errors + " error, " + warnings + " warning, "
        + count(r, Diagnostics.INFO) + " info) -> " + target;
  }

  private static long count(ExtractionResult r, String level) {
    return r.diagnostics().stream().map(Diagnostic::level).filter(level::equals).count();
  }

  /** Nom de fichier sûr : caractères hors [A-Za-z0-9._~-] remplacés par « _ ». */
  static String fileName(String name) {
    String s = name.replaceAll("[^A-Za-z0-9._~-]", "_");
    return s.isEmpty() || s.startsWith(".") ? "_" + s : s;
  }

  /** Version lue dans le manifeste du JAR ; {@code dev} hors JAR. */
  static final class Version implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      String v = Main.class.getPackage().getImplementationVersion();
      return new String[] {"java-meta-extractor " + (v == null ? "dev" : v)};
    }
  }
}
