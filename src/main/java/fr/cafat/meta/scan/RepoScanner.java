package fr.cafat.meta.scan;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Source;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Inventaire déterministe d'un dépôt : modules de build, fichiers sources et ressources de chaque
 * module (tests exclus), modules déployables.
 */
public final class RepoScanner {

  private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "build", "out", "bin",
      "node_modules", ".gradle", ".idea", ".mvn", ".settings", ".vscode", "target-kt");
  private static final Set<String> TEST_DIRS = Set.of("test", "tests", "testFixtures",
      "integrationTest", "integration-test", "it", "jmh");

  private final Path root;
  private final Diagnostics diagnostics;
  private final List<Module> modules;
  private final Map<Module, List<Path>> filesByModule = new LinkedHashMap<>();

  public RepoScanner(Path root, Diagnostics diagnostics) {
    this.root = root;
    this.diagnostics = diagnostics;
    this.modules = readModules();
    assignFiles();
  }

  public Path root() {
    return root;
  }

  public List<Module> modules() {
    return modules;
  }

  private List<Module> readModules() {
    List<Module> found;
    if (Files.isRegularFile(root.resolve("pom.xml"))) {
      found = new MavenReader(root, diagnostics).read();
    } else if (GradleReader.isGradle(root)) {
      try {
        found = new GradleReader(root).read();
      } catch (IOException e) {
        diagnostics.error("CONFIG_PARSE_ERROR", "build Gradle illisible : " + e.getMessage(), null);
        found = List.of();
      }
    } else {
      found = List.of();
    }
    if (found.isEmpty()) {
      found = List.of(new Module(":", root.getFileName().toString(), null, root, "", "jar", null,
          List.of(), List.of(), false, null));
    }
    List<Module> sorted = new ArrayList<>(found);
    // dédoublonnage (un même répertoire déclaré deux fois)
    Map<Path, Module> byDir = new LinkedHashMap<>();
    for (Module m : sorted) {
      byDir.putIfAbsent(m.dir().normalize(), m);
    }
    return List.copyOf(byDir.values());
  }

  private void assignFiles() {
    for (Module m : modules) {
      filesByModule.put(m, new ArrayList<>());
    }
    List<Module> byDepth = new ArrayList<>(modules);
    byDepth.sort(Comparator.comparingInt((Module m) -> m.dir().normalize().getNameCount()).reversed()
        .thenComparing(Module::relativeDir));
    for (Path file : walk(root)) {
      Path norm = file.normalize();
      for (Module m : byDepth) {
        Path dir = m.dir().normalize();
        if (norm.startsWith(dir)) {
          if (!isTestPath(dir.relativize(norm), hasSrcMain(dir))) {
            filesByModule.get(m).add(file);
          }
          break;
        }
      }
    }
  }

  private static List<Path> walk(Path root) {
    try (Stream<Path> s = Files.walk(root)) {
      return s.filter(Files::isRegularFile)
          .filter(p -> !hasSkippedSegment(root.relativize(p)))
          .sorted(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean hasSkippedSegment(Path rel) {
    for (int i = 0; i < rel.getNameCount() - 1; i++) {
      String seg = rel.getName(i).toString();
      if (SKIPPED_DIRS.contains(seg)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasSrcMain(Path dir) {
    return Files.isDirectory(dir.resolve("src").resolve("main"));
  }

  /**
   * Code de test : sous {@code src/<x>} avec x ≠ main pour un module au format Maven/Gradle, sinon
   * tout répertoire nommé test, tests, it…
   */
  static boolean isTestPath(Path relToModule, boolean srcMainLayout) {
    int n = relToModule.getNameCount();
    for (int i = 0; i < n - 1; i++) {
      String seg = relToModule.getName(i).toString();
      if (srcMainLayout && seg.equals("src") && i + 1 < n - 1) {
        return !relToModule.getName(i + 1).toString().equals("main");
      }
      if (!srcMainLayout && TEST_DIRS.contains(seg)) {
        return true;
      }
    }
    return false;
  }

  public List<Path> files(Module m) {
    return filesByModule.getOrDefault(m, List.of());
  }

  public List<Path> files(List<Module> ms, String... extensions) {
    List<Path> out = new ArrayList<>();
    for (Module m : ms) {
      for (Path p : files(m)) {
        String name = p.getFileName().toString();
        for (String ext : extensions) {
          if (name.endsWith(ext)) {
            out.add(p);
            break;
          }
        }
      }
    }
    out.sort(Comparator.comparing(p -> relative(p)));
    return out;
  }

  public String relative(Path p) {
    return root.relativize(p).toString().replace('\\', '/');
  }

  public Module moduleOf(Path file) {
    Module best = null;
    for (Module m : modules) {
      if (file.normalize().startsWith(m.dir().normalize())
          && (best == null || m.dir().getNameCount() > best.dir().getNameCount())) {
        best = m;
      }
    }
    return best;
  }

  // ---------------------------------------------------------------- unités déployables

  /** Module déployable et périmètre de modules analysés pour lui. */
  public record DeployableUnit(Module main, List<Module> modules, String kind) {
  }

  /**
   * EAR ; WAR hors EAR ; jar Spring Boot (annotation ou plugin) ; EJB hors EAR. Sans déployable,
   * le dépôt entier forme une unité ({@code NO_DEPLOYABLE_MODULE}).
   */
  public List<DeployableUnit> deployableUnits() {
    Map<String, Module> byKey = new LinkedHashMap<>();
    for (Module m : modules) {
      byKey.putIfAbsent(m.key(), m);
    }
    Set<Module> inEar = new LinkedHashSet<>();
    for (Module m : modules) {
      if ("ear".equals(m.packaging())) {
        inEar.addAll(closure(m, byKey));
        inEar.remove(m);
      }
    }
    List<DeployableUnit> units = new ArrayList<>();
    for (Module m : modules) {
      String kind = null;
      switch (m.packaging()) {
        case "ear" -> kind = "ear";
        case "war" -> kind = inEar.contains(m) ? null : "war";
        case "ejb" -> kind = inEar.contains(m) ? null : "ejb";
        case "pom" -> kind = null;
        default -> kind = !inEar.contains(m) && isSpringBootApp(m) ? "boot" : null;
      }
      if (kind != null) {
        units.add(new DeployableUnit(m, List.copyOf(closure(m, byKey)), kind));
      }
    }
    if (units.isEmpty()) {
      Module rootModule = modules.get(0);
      diagnostics.warning("NO_DEPLOYABLE_MODULE",
          "aucun module déployable détecté : le dépôt entier est analysé comme une application",
          Source.file(rootModule.buildFile(), null));
      units.add(new DeployableUnit(rootModule, modules, "repo"));
    }
    return units;
  }

  /** Le module et ses dépendances internes transitives, dans l'ordre de découverte. */
  private List<Module> closure(Module start, Map<String, Module> byKey) {
    Set<Module> seen = new LinkedHashSet<>();
    List<Module> todo = new ArrayList<>(List.of(start));
    while (!todo.isEmpty()) {
      Module m = todo.remove(0);
      if (!seen.add(m)) {
        continue;
      }
      for (String dep : m.dependencies()) {
        Module d = byKey.get(dep);
        if (d == null) {
          String artifact = dep.substring(dep.indexOf(':') + 1);
          for (Module c : modules) {
            if (artifact.equals(c.artifactId()) && !"pom".equals(c.packaging())) {
              d = c;
              break;
            }
          }
        }
        if (d != null) {
          todo.add(d);
        }
      }
    }
    return new ArrayList<>(seen);
  }

  private boolean isSpringBootApp(Module m) {
    for (Path p : files(m)) {
      String name = p.getFileName().toString();
      if (name.endsWith(".java") || name.endsWith(".kt")) {
        try {
          String text = Files.readString(p, StandardCharsets.ISO_8859_1);
          if (text.contains("@SpringBootApplication")) {
            return true;
          }
        } catch (IOException e) {
          // fichier illisible : signalé par le parseur
        }
      }
    }
    return false;
  }
}
