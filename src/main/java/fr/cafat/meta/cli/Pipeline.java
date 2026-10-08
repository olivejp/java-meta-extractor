package fr.cafat.meta.cli;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.ConfigLoader;
import fr.cafat.meta.config.DatasourceDetector;
import fr.cafat.meta.config.DatasourceDetector.Detected;
import fr.cafat.meta.config.PersistenceUnit;
import fr.cafat.meta.config.PersistenceXmlReader;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.PersistenceExtractor;
import fr.cafat.meta.extract.calls.CallExtractor;
import fr.cafat.meta.extract.entities.EntityDraft;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.extract.jms.JmsExtractor;
import fr.cafat.meta.extract.rest.EndpointExtractor;
import fr.cafat.meta.extract.sql.SqlExtractor;
import fr.cafat.meta.model.Application;
import fr.cafat.meta.model.Call;
import fr.cafat.meta.model.Datasource;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.Endpoint;
import fr.cafat.meta.model.Entity;
import fr.cafat.meta.model.ExtractionResult;
import fr.cafat.meta.model.Messaging;
import fr.cafat.meta.model.SqlAccess;
import fr.cafat.meta.output.Assembler;
import fr.cafat.meta.parse.SpoonLoader;
import fr.cafat.meta.resolve.DatasourceResolver;
import fr.cafat.meta.scan.GitInfo;
import fr.cafat.meta.scan.Module;
import fr.cafat.meta.scan.RepoScanner;
import fr.cafat.meta.scan.RepoScanner.DeployableUnit;
import fr.cafat.meta.scan.WebModule;
import fr.cafat.meta.scan.WebModules;
import fr.cafat.meta.spoon.Annotations;
import fr.cafat.meta.spoon.Provenance;
import fr.cafat.meta.spoon.TypeIndex;
import fr.cafat.meta.spoon.ValueEval;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

/**
 * Extraction complète d'un dépôt : un résultat par unité déployable (EAR, WAR, jar Spring Boot,
 * EJB), chacun construit sur son propre modèle Spoon et sa propre configuration.
 */
public final class Pipeline {

  /** Traduction Kotlin branchée par l'appelant (null : fichiers .kt ignorés avec un diagnostic). */
  @FunctionalInterface
  public interface KotlinFactory {
    SpoonLoader.KotlinStep create(Path repoRoot, Diagnostics diagnostics);
  }

  /**
   * @param commit commit à inscrire ; null : lu dans {@code .git} sans appel réseau
   * @param appName nom imposé (dépôt à une seule unité déployable), sinon null
   * @param profiles profils Spring actifs, dans l'ordre
   * @param viewSchemas schémas dont toutes les tables sont des vues
   */
  public record Options(String commit, String appName, List<String> profiles, Set<String> viewSchemas,
      KotlinFactory kotlin) {
  }

  /** Résultat d'une unité : nom de fichier de sortie (sans extension) et contenu. */
  public record Output(String name, ExtractionResult result) {
  }

  private static final Set<String> SPRING_BOOT = Set.of("org.springframework.boot.autoconfigure");
  private static final Set<String> EJB = Set.of("javax.ejb", "jakarta.ejb");

  private Pipeline() {
  }

  public static List<Output> run(Path repo, Options options) {
    Path root = repo.toAbsolutePath().normalize();
    Diagnostics scanDiagnostics = new Diagnostics();
    RepoScanner scanner = new RepoScanner(root, scanDiagnostics);
    List<DeployableUnit> units = scanner.deployableUnits();
    String repository = GitInfo.remoteOrigin(root);
    String commit = options.commit() != null ? options.commit() : GitInfo.headCommit(root);

    List<Prepared> prepared = new ArrayList<>();
    for (DeployableUnit unit : units) {
      prepared.add(prepare(scanner, unit, options));
    }
    List<String> names = names(prepared, options.appName());
    List<Output> out = new ArrayList<>();
    for (int i = 0; i < prepared.size(); i++) {
      Prepared p = prepared.get(i);
      Diagnostics diags = new Diagnostics();
      for (Diagnostic d : scanDiagnostics.all()) {
        diags.add(d.level(), d.code(), d.message(), d.source());
      }
      p.diagnostics().all().forEach(d -> diags.add(d.level(), d.code(), d.message(), d.source()));
      out.add(new Output(names.get(i), extract(scanner, p, names.get(i), repository, commit, options, diags)));
    }
    return out;
  }

  /** Fichiers et configuration d'une unité, avant l'analyse du code. */
  private record Prepared(DeployableUnit unit, Config config, List<PersistenceUnit> persistenceUnits,
      List<Path> java, List<Path> kotlin, List<Path> resources, Diagnostics diagnostics) {
  }

  private static Prepared prepare(RepoScanner scanner, DeployableUnit unit, Options options) {
    Diagnostics diags = new Diagnostics();
    // Configuration du module principal d'abord : ConfigLoader prend le premier fichier de chaque nom.
    List<Module> others = unit.modules().stream().filter(m -> m != unit.main()).toList();
    List<Path> configFiles = new ArrayList<>();
    for (List<Module> ms : List.of(List.of(unit.main()), others)) {
      scanner.files(ms, ".yml", ".yaml", ".properties").stream()
          .filter(ConfigLoader::isConfigFile).forEach(configFiles::add);
    }
    Config config = new ConfigLoader(diags, scanner::relative,
        (f, text) -> scanner.moduleOf(f).filtering().apply(f, text)).load(configFiles, options.profiles());
    List<Path> resources = scanner.files(unit.modules(), ".xml");
    List<PersistenceUnit> pus = new ArrayList<>();
    for (Path f : resources) {
      if (f.getFileName().toString().equals("persistence.xml")) {
        pus.addAll(PersistenceXmlReader.read(f, scanner.relative(f), scanner.moduleOf(f).dir(), diags));
      }
    }
    return new Prepared(unit, config, pus, scanner.files(unit.modules(), ".java"),
        scanner.files(unit.modules(), ".kt"), resources, diags);
  }

  /**
   * Nom de chaque unité : nom imposé (une seule unité), {@code spring.application.name}, sinon
   * artifactId du module principal. Deux unités de même nom reçoivent le suffixe de leur artifactId.
   */
  static List<String> names(List<Prepared> prepared, String forced) {
    List<String> names = new ArrayList<>();
    for (Prepared p : prepared) {
      String name = prepared.size() == 1 && forced != null ? forced : null;
      if (name == null) {
        String spring = p.config().resolve(p.config().get("spring.application.name")).value();
        name = spring != null && !spring.isBlank() && !spring.contains("${") ? spring.strip()
            : p.unit().main().artifactId();
      }
      names.add(name);
    }
    Map<String, Integer> counts = new HashMap<>();
    names.forEach(n -> counts.merge(n, 1, Integer::sum));
    for (int i = 0; i < names.size(); i++) {
      String artifact = prepared.get(i).unit().main().artifactId();
      if (counts.get(names.get(i)) > 1 && !names.get(i).equals(artifact)) {
        names.set(i, names.get(i) + "-" + artifact);
      }
    }
    return names;
  }

  private static ExtractionResult extract(RepoScanner scanner, Prepared p, String appId, String repository,
      String commit, Options options, Diagnostics diags) {
    Path root = scanner.root();
    Provenance provenance = new Provenance(root);
    SpoonLoader.KotlinStep kotlin = options.kotlin() == null ? null : options.kotlin().create(root, diags);
    if (kotlin == null && !p.kotlin().isEmpty()) {
      diags.warning("KOTLIN_SKIPPED", p.kotlin().size() + " fichier(s) Kotlin non analysé(s)",
          fr.cafat.meta.model.Source.file(scanner.relative(p.kotlin().get(0)), null));
    }
    CtModel model = SpoonLoader.load(p.java(), p.kotlin(), kotlin, diags, provenance::relative);
    TypeIndex index = new TypeIndex(model);
    boolean springBoot = isSpringBoot(p.unit(), index);
    List<WebModule> webModules = WebModules.detect(p.unit(), p.config(), diags, scanner::relative);
    NamingStrategy naming = NamingStrategy.detect(p.config(), p.persistenceUnits(), springBoot, diags);
    ExtractionContext ctx = new ExtractionContext(appId, root, model, index, new ValueEval(index), provenance,
        diags, p.config(), naming, options.viewSchemas(), p.persistenceUnits(), p.resources(), webModules);

    PersistenceExtractor.Result persistence = PersistenceExtractor.run(ctx);
    List<SqlExtractor.SqlDraft> sql = new SqlExtractor(ctx).extract();
    List<Detected> detected = DatasourceDetector.detect(p.config(), p.persistenceUnits());
    DatasourceResolver.Result resolved = new DatasourceResolver(ctx, detected, persistence.drafts())
        .resolve(sql, persistence.relations());
    List<Endpoint> endpoints = new EndpointExtractor(ctx).extract();
    List<Call> calls = new CallExtractor(ctx).extract();
    List<Messaging> messaging = new JmsExtractor(ctx).extract();

    List<EntityDraft> drafts = new ArrayList<>(persistence.drafts().values());
    drafts.sort(Comparator.comparing(d -> d.id));
    List<Entity> entities = drafts.stream().map(EntityDraft::toEntity).toList();

    Map<String, Datasource> datasources = new LinkedHashMap<>();
    detected.forEach(d -> datasources.putIfAbsent(d.datasource().id(), d.datasource()));
    Module main = p.unit().main();
    String version = main.version() == null || main.version().contains("${") ? null : main.version();
    Application app = new Application(appId, appId, version,
        tech(p, index, springBoot, entities, resolved.sqlAccesses(), endpoints, messaging),
        repository, commit, main.relativeDir().isEmpty() ? null : main.relativeDir(), options.profiles(),
        contextPath(webModules), List.copyOf(datasources.values()));
    return Assembler.assemble(app, entities, resolved.relations(), resolved.sqlAccesses(), endpoints, calls,
        messaging, diags.all());
  }

  private static boolean isSpringBoot(DeployableUnit unit, TypeIndex index) {
    if ("boot".equals(unit.kind()) || unit.modules().stream().anyMatch(Module::springBootPlugin)) {
      return true;
    }
    for (CtType<?> t : index.all()) {
      if (Annotations.has(t, SPRING_BOOT, "SpringBootApplication")) {
        return true;
      }
    }
    return false;
  }

  /** Technologies constatées dans le code et la configuration de l'unité (ordre alphabétique). */
  private static List<String> tech(Prepared p, TypeIndex index, boolean springBoot, List<Entity> entities,
      List<SqlAccess> sql, List<Endpoint> endpoints, List<Messaging> messaging) {
    Set<String> tech = new TreeSet<>();
    if (springBoot) {
      tech.add("spring-boot");
    }
    boolean ejb = "ejb".equals(p.unit().main().packaging())
        || p.unit().modules().stream().anyMatch(m -> "ejb".equals(m.packaging()));
    boolean spring = false;
    for (CtType<?> t : index.all()) {
      if (!ejb && Annotations.findAny(t, EJB, "Stateless", "Stateful", "Singleton", "MessageDriven") != null) {
        ejb = true;
      }
      if (!spring && t.getAnnotations().stream().anyMatch(a -> a.getAnnotationType().getQualifiedName()
          .startsWith("org.springframework."))) {
        spring = true;
      }
    }
    if (ejb) {
      tech.add("ejb");
    }
    if (spring || springBoot) {
      tech.add("spring");
    }
    if ("ear".equals(p.unit().kind()) || p.resources().stream()
        .anyMatch(f -> f.getFileName().toString().startsWith("jboss-"))
        || p.persistenceUnits().stream().anyMatch(u -> u.jtaDataSource() != null
            && u.jtaDataSource().startsWith("java:jboss/"))) {
      tech.add("jboss");
    }
    if (endpoints.stream().anyMatch(e -> EndpointExtractor.JAX_RS.equals(e.framework()))) {
      tech.add("jax-rs");
    }
    if (!entities.isEmpty() || !p.persistenceUnits().isEmpty()) {
      tech.add("jpa");
    }
    if (sql.stream().anyMatch(s -> s.origin().startsWith("mybatis"))) {
      tech.add("mybatis");
    }
    if (!messaging.isEmpty()) {
      tech.add("jms");
    }
    if (!p.kotlin().isEmpty()) {
      tech.add("kotlin");
    }
    if (!p.java().isEmpty()) {
      tech.add("java");
    }
    return List.copyOf(tech);
  }

  /** Chemin de contexte de l'unique module web ; null si aucun ou plusieurs. */
  private static String contextPath(List<WebModule> webModules) {
    if (webModules.size() != 1) {
      return null;
    }
    String c = webModules.get(0).contextPath();
    return c.isEmpty() ? "/" : c;
  }
}
