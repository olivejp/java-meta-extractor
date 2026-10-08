package fr.cafat.meta;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.ConfigLoader;
import fr.cafat.meta.config.PersistenceUnit;
import fr.cafat.meta.config.PersistenceXmlReader;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.PersistenceExtractor;
import fr.cafat.meta.extract.entities.EntityDraft;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.parse.SpoonLoader;
import fr.cafat.meta.scan.RepoScanner;
import fr.cafat.meta.scan.WebModule;
import fr.cafat.meta.scan.WebModules;
import fr.cafat.meta.spoon.Provenance;
import fr.cafat.meta.spoon.TypeIndex;
import fr.cafat.meta.spoon.ValueEval;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import spoon.reflect.CtModel;

/** Construction de contextes d'extraction pour les tests. */
public final class TestContexts {

  public static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath();
  private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
  private static final Pattern TYPE = Pattern.compile(
      "\\b(?:class|interface|enum|record)\\s+(\\w+)");

  private TestContexts() {
  }

  public static Path fixture(String name) {
    return FIXTURES.resolve(name);
  }

  /** Contexte sur les sources Java (hors src/test) d'un dépôt de test. */
  public static ExtractionContext ofRepo(Path root, String appId, NamingStrategy naming) {
    Diagnostics diags = new Diagnostics();
    RepoScanner scanner = new RepoScanner(root, diags);
    List<Path> java = scanner.files(scanner.modules(), ".java");
    return build(root, java, appId, naming, diags, List.of(WebModule.ROOT), Config.empty(),
        scanner.files(scanner.modules(), ".xml"));
  }

  /** Comme {@link #ofRepo(Path, String, NamingStrategy)}, avec les modules web de toutes les unités. */
  public static ExtractionContext ofRepoWeb(Path root, String appId, NamingStrategy naming) {
    Diagnostics diags = new Diagnostics();
    RepoScanner scanner = new RepoScanner(root, diags);
    List<Path> java = scanner.files(scanner.modules(), ".java");
    return build(root, java, appId, naming, diags, webModules(scanner, diags), Config.empty(),
        scanner.files(scanner.modules(), ".xml"));
  }

  /** Comme {@link #ofRepoWeb}, avec la configuration de toutes les unités chargée pour les profils donnés. */
  public static ExtractionContext ofRepoConfigured(Path root, String appId, NamingStrategy naming,
      List<String> profiles) {
    Diagnostics diags = new Diagnostics();
    RepoScanner scanner = new RepoScanner(root, diags);
    List<Path> java = scanner.files(scanner.modules(), ".java");
    List<Path> configFiles = scanner.files(scanner.modules(), ".yml", ".yaml", ".properties").stream()
        .filter(ConfigLoader::isConfigFile).toList();
    Config config = new ConfigLoader(diags, scanner::relative,
        (f, text) -> scanner.moduleOf(f).filtering().apply(f, text)).load(configFiles, profiles);
    List<Path> xml = scanner.files(scanner.modules(), ".xml");
    List<PersistenceUnit> units = new ArrayList<>();
    for (Path f : xml) {
      if (f.getFileName().toString().equals("persistence.xml")) {
        units.addAll(PersistenceXmlReader.read(f, scanner.relative(f), scanner.moduleOf(f).dir(), diags));
      }
    }
    return build(root, java, appId, naming, diags, webModules(scanner, diags), config, xml, units);
  }

  /** Modules web de chaque unité déployable, configuration Spring chargée sans profil. */
  public static List<WebModule> webModules(RepoScanner scanner, Diagnostics diags) {
    List<WebModule> out = new ArrayList<>();
    for (RepoScanner.DeployableUnit unit : scanner.deployableUnits()) {
      List<Path> configFiles = scanner.files(unit.modules(), ".yml", ".yaml", ".properties").stream()
          .filter(ConfigLoader::isConfigFile).toList();
      Config config = new ConfigLoader(diags, scanner::relative,
          (f, text) -> scanner.moduleOf(f).filtering().apply(f, text)).load(configFiles, List.of());
      out.addAll(WebModules.detect(unit, config, diags, scanner::relative));
    }
    return out;
  }

  /** Contexte sur des sources écrites dans {@code dir} (une unité de compilation par chaîne). */
  public static ExtractionContext ofSources(Path dir, NamingStrategy naming, String... sources) {
    try {
      List<Path> files = new java.util.ArrayList<>();
      for (String src : sources) {
        Matcher p = PACKAGE.matcher(src);
        Matcher t = TYPE.matcher(src);
        if (!t.find()) {
          throw new IllegalArgumentException("type introuvable : " + src);
        }
        Path pkg = p.find() ? dir.resolve(p.group(1).replace('.', '/')) : dir;
        Files.createDirectories(pkg);
        Path f = pkg.resolve(t.group(1) + ".java");
        Files.writeString(f, src, StandardCharsets.UTF_8);
        files.add(f);
      }
      files.sort(null);
      return build(dir, files, "app", naming, new Diagnostics());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ExtractionContext build(Path root, List<Path> java, String appId, NamingStrategy naming,
      Diagnostics diags) {
    return build(root, java, appId, naming, diags, List.of(WebModule.ROOT));
  }

  public static ExtractionContext build(Path root, List<Path> java, String appId, NamingStrategy naming,
      Diagnostics diags, List<WebModule> webModules) {
    return build(root, java, appId, naming, diags, webModules, Config.empty());
  }

  public static ExtractionContext build(Path root, List<Path> java, String appId, NamingStrategy naming,
      Diagnostics diags, List<WebModule> webModules, Config config) {
    return build(root, java, appId, naming, diags, webModules, config, List.of());
  }

  public static ExtractionContext build(Path root, List<Path> java, String appId, NamingStrategy naming,
      Diagnostics diags, List<WebModule> webModules, Config config, List<Path> resources) {
    return build(root, java, appId, naming, diags, webModules, config, resources, List.of());
  }

  public static ExtractionContext build(Path root, List<Path> java, String appId, NamingStrategy naming,
      Diagnostics diags, List<WebModule> webModules, Config config, List<Path> resources,
      List<PersistenceUnit> units) {
    Provenance prov = new Provenance(root);
    CtModel model = SpoonLoader.load(java, List.of(), null, diags, prov::relative);
    TypeIndex index = new TypeIndex(model);
    return new ExtractionContext(appId, root, model, index, new ValueEval(index), prov, diags, config,
        naming, Set.of("MGENGPP"), units, resources, webModules);
  }

  /** Entités extraites, liées, reliées et propagées. */
  public static Map<String, EntityDraft> entities(ExtractionContext ctx) {
    return PersistenceExtractor.run(ctx).drafts();
  }

  public static List<String> codes(ExtractionContext ctx) {
    return ctx.diagnostics().all().stream().map(Diagnostic::code).toList();
  }
}
