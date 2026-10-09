package fr.cafat.meta.scan;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.extract.Diagnostics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleReaderTest {

  private static void write(Path root, String file, String text) throws IOException {
    Path f = root.resolve(file);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }

  @Test
  void detectionDuBuildGradle(@TempDir Path repo) throws IOException {
    assertThat(GradleReader.isGradle(repo)).isFalse();
    write(repo, "settings.gradle.kts", "rootProject.name = \"x\"");
    assertThat(GradleReader.isGradle(repo)).isTrue();
  }

  @Test
  void projetsInclusNomRacineEtVersion(@TempDir Path repo) throws IOException {
    write(repo, "settings.gradle", """
        rootProject.name = 's-gen-demo'
        include 'commun', ':web'
        include(":services:metier")
        """);
    write(repo, "build.gradle", "version = '3.1.0'\n");
    write(repo, "commun/build.gradle", "plugins { id 'java-library' }\nversion = '9.9'\n");
    write(repo, "web/build.gradle", """
        plugins { id 'war' }
        dependencies { implementation project(':commun') }
        war { archiveFileName = 'demo.war' }
        """);
    write(repo, "services/metier/build.gradle.kts", """
        dependencies { implementation(project(path = ":commun")) }
        """);

    List<Module> modules = new GradleReader(repo).read();

    assertThat(modules).extracting(Module::key).containsExactly(":", ":commun", ":web", ":services:metier");
    assertThat(modules).extracting(Module::artifactId).containsExactly("s-gen-demo", "commun", "web", "metier");
    assertThat(modules).extracting(Module::version).containsExactly("3.1.0", "9.9", "3.1.0", "3.1.0");
    assertThat(modules.get(0).children()).containsExactly(":commun", ":web", ":services:metier");
    Module web = modules.get(2);
    assertThat(web.packaging()).isEqualTo("war");
    assertThat(web.finalName()).isEqualTo("demo.war");
    assertThat(web.dependencies()).containsExactly(":commun");
    assertThat(web.buildFile()).isEqualTo("web/build.gradle");
    Module metier = modules.get(3);
    assertThat(metier.relativeDir()).isEqualTo("services/metier");
    assertThat(metier.buildFile()).isEqualTo("services/metier/build.gradle.kts");
    assertThat(metier.dependencies()).containsExactly(":commun");
    assertThat(modules.get(1).packaging()).isEqualTo("jar");
  }

  @Test
  void sansSettingsNomDuRepertoire(@TempDir Path repo) throws IOException {
    write(repo, "build.gradle.kts", "plugins { `ear` }\n");

    List<Module> modules = new GradleReader(repo).read();

    assertThat(modules).hasSize(1);
    assertThat(modules.get(0).artifactId()).isEqualTo(repo.getFileName().toString());
    assertThat(modules.get(0).packaging()).isEqualTo("ear");
    assertThat(modules.get(0).relativeDir()).isEmpty();
  }

  @Test
  void detectionDeSpringBoot(@TempDir Path repo) throws IOException {
    write(repo, "settings.gradle", "include 'app', 'lib'\n");
    write(repo, "build.gradle", "plugins { id 'org.springframework.boot' version '3.2.0' apply false }\n");
    write(repo, "app/build.gradle", "plugins { id 'org.springframework.boot' }\n");
    write(repo, "lib/build.gradle", "plugins { id 'java-library' }\n");

    List<Module> modules = new GradleReader(repo).read();

    assertThat(modules).extracting(Module::springBootPlugin).containsExactly(false, true, false);
  }

  @Test
  void depotGradleLuParLeScanner(@TempDir Path repo) throws IOException {
    write(repo, "settings.gradle", "include 'web'\n");
    write(repo, "build.gradle", "");
    write(repo, "web/build.gradle", "apply plugin: 'war'\n");
    write(repo, "web/src/main/java/nc/A.java", "class A {}");

    RepoScanner scanner = new RepoScanner(repo, new Diagnostics());

    assertThat(scanner.deployableUnits()).extracting(u -> u.main().key(), RepoScanner.DeployableUnit::kind)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(":web", "war"));
    assertThat(scanner.files(scanner.modules(), ".java")).extracting(scanner::relative)
        .containsExactly("web/src/main/java/nc/A.java");
  }
}
