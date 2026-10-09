package fr.cafat.meta.scan;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoScannerTest {

  private static void write(Path root, String file, String text) throws IOException {
    Path f = root.resolve(file);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }

  /** pom minimal ; {@code extra} est inséré tel quel dans {@code <project>}. */
  private static String pom(String artifactId, String packaging, String extra) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>nc.cafat</groupId>"
        + "<artifactId>" + artifactId + "</artifactId><version>1.0</version>"
        + (packaging == null ? "" : "<packaging>" + packaging + "</packaging>") + extra + "</project>";
  }

  private static String modules(String... names) {
    StringBuilder sb = new StringBuilder("<modules>");
    for (String n : names) {
      sb.append("<module>").append(n).append("</module>");
    }
    return sb.append("</modules>").toString();
  }

  private static String deps(String... artifactIds) {
    StringBuilder sb = new StringBuilder("<dependencies>");
    for (String a : artifactIds) {
      sb.append("<dependency><groupId>nc.cafat</groupId><artifactId>").append(a)
          .append("</artifactId></dependency>");
    }
    return sb.append("</dependencies>").toString();
  }

  private static List<String> artifacts(List<Module> modules) {
    return modules.stream().map(Module::artifactId).toList();
  }

  @Test
  void modulesMavenMultiNiveauxEtHeritageDuParent(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", "<properties><socle.version>2.3</socle.version></properties>"
        + modules("services", "web")));
    write(repo, "services/pom.xml", """
        <project><parent><groupId>nc.cafat</groupId><artifactId>racine</artifactId><version>1.0</version></parent>
        <artifactId>services</artifactId><packaging>pom</packaging>
        <modules><module>metier</module></modules></project>""");
    write(repo, "services/metier/pom.xml", """
        <project><parent><groupId>nc.cafat</groupId><artifactId>services</artifactId><version>1.0</version></parent>
        <artifactId>metier</artifactId><version>${socle.version}</version>
        <dependencies><dependency><groupId>nc.cafat</groupId><artifactId>outil</artifactId></dependency>
        <dependency><groupId>org.junit</groupId><artifactId>junit</artifactId><scope>test</scope></dependency>
        </dependencies></project>""");
    write(repo, "web/pom.xml", pom("web", "war", "<build><finalName>${project.artifactId}-app</finalName></build>"));

    List<Module> modules = new RepoScanner(repo, new Diagnostics()).modules();

    assertThat(artifacts(modules)).containsExactly("racine", "services", "metier", "web");
    Module metier = modules.get(2);
    assertThat(metier.key()).isEqualTo("nc.cafat:metier");
    assertThat(metier.version()).isEqualTo("2.3");
    assertThat(metier.packaging()).isEqualTo("jar");
    assertThat(metier.relativeDir()).isEqualTo("services/metier");
    assertThat(metier.buildFile()).isEqualTo("services/metier/pom.xml");
    assertThat(metier.dependencies()).containsExactly("nc.cafat:outil");
    assertThat(modules.get(3).finalName()).isEqualTo("web-app");
    assertThat(modules.get(0).children()).containsExactly("services", "web");
  }

  @Test
  void moduleIntrouvableEtPomIllisibleSignales(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", modules("absent", "casse")));
    write(repo, "casse/pom.xml", "<project><artifactId>casse");
    Diagnostics diags = new Diagnostics();

    List<Module> modules = new RepoScanner(repo, diags).modules();

    assertThat(artifacts(modules)).containsExactly("racine");
    assertThat(diags.all()).extracting(Diagnostic::code).containsExactly("CONFIG_PARSE_ERROR", "CONFIG_PARSE_ERROR");
    assertThat(diags.all()).extracting(Diagnostic::level).containsExactly("warning", "error");
  }

  @Test
  void sansBuildLeDepotEstUnModuleJar(@TempDir Path repo) throws IOException {
    write(repo, "src/A.java", "class A {}");

    List<Module> modules = new RepoScanner(repo, new Diagnostics()).modules();

    assertThat(modules).hasSize(1);
    assertThat(modules.get(0).packaging()).isEqualTo("jar");
    assertThat(modules.get(0).artifactId()).isEqualTo(repo.getFileName().toString());
    assertThat(modules.get(0).relativeDir()).isEmpty();
  }

  @Test
  void fichiersParModuleSansTestsNiRepertoiresDeBuild(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", modules("app")));
    write(repo, "app/pom.xml", pom("app", "jar", ""));
    write(repo, "app/src/main/java/nc/B.java", "class B {}");
    write(repo, "app/src/main/java/nc/A.java", "class A {}");
    write(repo, "app/src/main/resources/application.yml", "a: 1");
    write(repo, "app/src/test/java/nc/ATest.java", "class ATest {}");
    write(repo, "app/src/it/java/nc/AIT.java", "class AIT {}");
    write(repo, "app/target/classes/Gen.java", "class Gen {}");
    write(repo, "app/node_modules/x/index.java", "class X {}");
    write(repo, "outils/Script.java", "class Script {}");
    RepoScanner scanner = new RepoScanner(repo, new Diagnostics());
    Module app = scanner.modules().get(1);

    assertThat(scanner.files(app)).extracting(scanner::relative).containsExactly(
        "app/pom.xml", "app/src/main/java/nc/A.java", "app/src/main/java/nc/B.java",
        "app/src/main/resources/application.yml");
    assertThat(scanner.files(scanner.modules(), ".java")).extracting(scanner::relative)
        .containsExactly("app/src/main/java/nc/A.java", "app/src/main/java/nc/B.java", "outils/Script.java");
    assertThat(scanner.files(List.of(app), ".yml", ".xml")).extracting(scanner::relative)
        .containsExactly("app/pom.xml", "app/src/main/resources/application.yml");
  }

  @Test
  void testsExclusHorsDispositionMaven(@TempDir Path repo) throws IOException {
    write(repo, "java/nc/A.java", "class A {}");
    write(repo, "test/nc/ATest.java", "class ATest {}");
    write(repo, "java/nc/tests/Outil.java", "class Outil {}");
    RepoScanner scanner = new RepoScanner(repo, new Diagnostics());

    assertThat(scanner.files(scanner.modules(), ".java")).extracting(scanner::relative)
        .containsExactly("java/nc/A.java");
  }

  @Test
  void moduleLePlusProfond(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", modules("a")));
    write(repo, "a/pom.xml", pom("a", "jar", ""));
    RepoScanner scanner = new RepoScanner(repo, new Diagnostics());

    assertThat(scanner.moduleOf(repo.resolve("a/src/main/java/X.java")).artifactId()).isEqualTo("a");
    assertThat(scanner.moduleOf(repo.resolve("doc/x.md")).artifactId()).isEqualTo("racine");
    assertThat(scanner.moduleOf(repo.getParent().resolve("ailleurs/x.java"))).isNull();
    assertThat(scanner.relative(repo.resolve("a/pom.xml"))).isEqualTo("a/pom.xml");
  }

  @Test
  void earAvecSesWarEtEjbEtDependancesInternes(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", modules("commun", "ejb", "web", "ear", "admin")));
    write(repo, "commun/pom.xml", pom("commun", "jar", ""));
    write(repo, "ejb/pom.xml", pom("ejb", "ejb", deps("commun")));
    write(repo, "web/pom.xml", pom("web", "war", deps("ejb", "commons-lang3")));
    write(repo, "ear/pom.xml", pom("ear", "ear", deps("web", "ejb")));
    write(repo, "admin/pom.xml", pom("admin", "war", deps("commun")));

    List<RepoScanner.DeployableUnit> units = new RepoScanner(repo, new Diagnostics()).deployableUnits();

    assertThat(units).extracting(u -> u.main().artifactId(), RepoScanner.DeployableUnit::kind)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("ear", "ear"),
            org.assertj.core.groups.Tuple.tuple("admin", "war"));
    assertThat(artifacts(units.get(0).modules())).containsExactly("ear", "web", "ejb", "commun");
    assertThat(artifacts(units.get(1).modules())).containsExactly("admin", "commun");
  }

  @Test
  void warEtEjbHorsEar(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", modules("web", "ejb")));
    write(repo, "web/pom.xml", pom("web", "war", ""));
    write(repo, "ejb/pom.xml", pom("ejb", "ejb", ""));

    List<RepoScanner.DeployableUnit> units = new RepoScanner(repo, new Diagnostics()).deployableUnits();

    assertThat(units).extracting(RepoScanner.DeployableUnit::kind).containsExactly("war", "ejb");
  }

  @Test
  void jarSpringBootParAnnotation(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", modules("lib", "app")));
    write(repo, "lib/pom.xml", pom("lib", "jar", ""));
    write(repo, "lib/src/main/java/nc/Lib.java", "class Lib {}");
    write(repo, "app/pom.xml", pom("app", "jar", deps("lib")));
    write(repo, "app/src/main/java/nc/App.java", "@SpringBootApplication class App {}");

    List<RepoScanner.DeployableUnit> units = new RepoScanner(repo, new Diagnostics()).deployableUnits();

    assertThat(units).hasSize(1);
    assertThat(units.get(0).kind()).isEqualTo("boot");
    assertThat(units.get(0).main().artifactId()).isEqualTo("app");
    assertThat(artifacts(units.get(0).modules())).containsExactly("app", "lib");
  }

  @Test
  void jarSpringBootParPluginMaven(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", modules("lib", "app")
        + "<build><pluginManagement><plugins><plugin><groupId>org.springframework.boot</groupId>"
        + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></pluginManagement></build>"));
    write(repo, "lib/pom.xml", pom("lib", "jar", ""));
    write(repo, "lib/src/main/java/nc/Lib.java", "class Lib {}");
    write(repo, "app/pom.xml", pom("app", "jar", deps("lib")
        + "<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
        + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>"));
    write(repo, "app/src/main/java/nc/App.java", "class App { public static void main(String[] a) {} }");
    Diagnostics diags = new Diagnostics();

    List<RepoScanner.DeployableUnit> units = new RepoScanner(repo, diags).deployableUnits();

    assertThat(units).hasSize(1);
    assertThat(units.get(0).kind()).isEqualTo("boot");
    assertThat(units.get(0).main().artifactId()).isEqualTo("app");
    assertThat(artifacts(units.get(0).modules())).containsExactly("app", "lib");
    assertThat(diags.all()).isEmpty();
  }

  @Test
  void sansDeployableLeDepotEntierEstUneUnite(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", pom("racine", "pom", modules("lib")));
    write(repo, "lib/pom.xml", pom("lib", "jar", ""));
    Diagnostics diags = new Diagnostics();

    List<RepoScanner.DeployableUnit> units = new RepoScanner(repo, diags).deployableUnits();

    assertThat(units).hasSize(1);
    assertThat(units.get(0).kind()).isEqualTo("repo");
    assertThat(units.get(0).main().artifactId()).isEqualTo("racine");
    assertThat(artifacts(units.get(0).modules())).containsExactly("racine", "lib");
    assertThat(diags.all()).extracting(Diagnostic::code).containsExactly("NO_DEPLOYABLE_MODULE");
    assertThat(diags.all().get(0).source().file()).isEqualTo("pom.xml");
  }
}
