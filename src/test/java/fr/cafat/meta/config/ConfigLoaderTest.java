package fr.cafat.meta.config;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

  @TempDir
  Path dir;

  private final Diagnostics diags = new Diagnostics();

  private Path write(String file, String text) throws IOException {
    Path f = dir.resolve(file);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
    return f;
  }

  private Config load(List<Path> files, String... profiles) {
    return new ConfigLoader(diags, p -> dir.relativize(p).toString()).load(files, List.of(profiles));
  }

  private List<String> codes() {
    return diags.all().stream().map(Diagnostic::code).toList();
  }

  @Test
  void baseDocumentsConditionnesPuisFichiersDeProfilDansLOrdre() throws IOException {
    Path base = write("application.yml", """
        a: base
        b: base
        c: base
        d: base
        ---
        spring:
          config:
            activate:
              on-profile: prod
        b: doc-prod
        c: doc-prod
        d: doc-prod
        """);
    Path prod = write("application-prod.yml", "c: fichier-prod\nd: fichier-prod\n");
    Path extra = write("application-extra.properties", "d=fichier-extra\n");

    Config c = load(List.of(base, prod, extra), "prod", "extra");

    assertThat(c.get("a")).isEqualTo("base");
    assertThat(c.get("b")).isEqualTo("doc-prod");
    assertThat(c.get("c")).isEqualTo("fichier-prod");
    assertThat(c.get("d")).isEqualTo("fichier-extra");
    assertThat(c.entry("d").file()).isEqualTo("application-extra.properties");
    assertThat(c.entry("d").line()).isEqualTo(1);
    assertThat(codes()).isEmpty();
  }

  @Test
  void ordreDesProfilsDemandes() throws IOException {
    Path a = write("application-a.yml", "x: a\n");
    Path b = write("application-b.yml", "x: b\n");

    assertThat(load(List.of(a, b), "a", "b").get("x")).isEqualTo("b");
    assertThat(load(List.of(a, b), "b", "a").get("x")).isEqualTo("a");
  }

  @Test
  void documentConditionnelIgnoreSansSonProfil() throws IOException {
    Path base = write("application.yml", "u: base\n---\nspring:\n  profiles: prod\nu: prod\n");

    assertThat(load(List.of(base), "dev").get("u")).isEqualTo("base");
    assertThat(load(List.of(base), "prod").get("u")).isEqualTo("prod");
  }

  @Test
  void documentConditionnelDansUnFichierDeProfil() throws IOException {
    Path dev = write("application-dev.yml", "u: dev\n---\nspring:\n  profiles: local\nu: dev-local\n");

    assertThat(load(List.of(dev), "dev").get("u")).isEqualTo("dev");
    assertThat(load(List.of(dev), "dev", "local").get("u")).isEqualTo("dev-local");
  }

  @Test
  void proprietesPrioritairesSurYamlEtApplicationSurBootstrap() throws IOException {
    Path boot = write("bootstrap.yml", "spring:\n  application:\n    name: s-boot\nx: bootstrap\n");
    Path yml = write("application.yml", "x: yml\ny: yml\n");
    Path props = write("application.properties", "y=properties\n");

    Config c = load(List.of(boot, yml, props));

    assertThat(c.get("spring.application.name")).isEqualTo("s-boot");
    assertThat(c.get("x")).isEqualTo("yml");
    assertThat(c.get("y")).isEqualTo("properties");
  }

  @Test
  void premierFichierDeChaqueNomGagne() throws IOException {
    Path principal = write("app/src/main/resources/application.yml", "a: principal\n");
    Path dependance = write("lib/src/main/resources/application.yml", "a: dependance\nb: dependance\n");

    Config c = load(List.of(principal, dependance));

    assertThat(c.get("a")).isEqualTo("principal");
    assertThat(c.get("b")).isNull();
  }

  @Test
  void expressionsDeProfil() {
    assertThat(ConfigLoader.matches("a,b", List.of("b"))).isTrue();
    assertThat(ConfigLoader.matches("a | b", List.of("b"))).isTrue();
    assertThat(ConfigLoader.matches("a & b", List.of("a"))).isFalse();
    assertThat(ConfigLoader.matches("a & b", List.of("a", "b"))).isTrue();
    assertThat(ConfigLoader.matches("!a", List.of("b"))).isTrue();
    assertThat(ConfigLoader.matches("!a", List.of("a"))).isFalse();
    assertThat(ConfigLoader.matches("(a & !b) | c", List.of("a"))).isTrue();
    assertThat(ConfigLoader.matches(null, List.of())).isTrue();
  }

  @Test
  void profilDisponibleMaisNonDemande() throws IOException {
    Path base = write("application.yml", "spring:\n  profiles:\n    active: dev\n---\nspring:\n  profiles: recette\n");
    Path prod = write("application-prod.yml", "a: 1\n");

    load(List.of(base, prod));

    assertThat(diags.all()).singleElement().satisfies(d -> {
      assertThat(d.code()).isEqualTo("PROFILE_NOT_APPLIED");
      assertThat(d.level()).isEqualTo(Diagnostics.INFO);
      assertThat(d.message()).contains("prod, recette").contains("spring.profiles.active=dev");
    });
  }

  @Test
  void sansProfilDisponiblePasDeDiagnostic() throws IOException {
    load(List.of(write("application.yml", "a: 1\n")));

    assertThat(codes()).isEmpty();
  }

  @Test
  void yamlIllisibleSignaleEtIgnore() throws IOException {
    Path broken = write("application.yml", "a: [1, 2\nb: : :\n");
    Path props = write("application.properties", "c=ok\n");

    Config c = load(List.of(broken, props));

    assertThat(c.get("c")).isEqualTo("ok");
    assertThat(diags.all()).singleElement().satisfies(d -> {
      assertThat(d.code()).isEqualTo("CONFIG_PARSE_ERROR");
      assertThat(d.level()).isEqualTo(Diagnostics.ERROR);
      assertThat(d.source().file()).isEqualTo("application.yml");
    });
  }

  @Test
  void filtreDeBuildAppliqueAvantLaLecture() throws IOException {
    Path base = write("application.yml", "spring:\n  application:\n    name: '@artifactId@'\n");

    Config c = new ConfigLoader(diags, Path::toString, (f, text) -> text.replace("@artifactId@", "s-filtre"))
        .load(List.of(base), List.of());

    assertThat(c.get("spring.application.name")).isEqualTo("s-filtre");
  }

  @Test
  void fichiersCandidats() {
    assertThat(ConfigLoader.isConfigFile(Path.of("application-prod.yml"))).isTrue();
    assertThat(ConfigLoader.isConfigFile(Path.of("bootstrap.properties"))).isTrue();
    assertThat(ConfigLoader.isConfigFile(Path.of("application.yaml"))).isTrue();
    assertThat(ConfigLoader.isConfigFile(Path.of("logback.xml"))).isFalse();
    assertThat(ConfigLoader.isConfigFile(Path.of("messages.properties"))).isFalse();
  }

  @Test
  void decodageUtf8SinonLatin1() {
    assertThat(ConfigLoader.decode("é".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo("é");
    assertThat(ConfigLoader.decode(new byte[] {(byte) 0xE9})).isEqualTo("é");
  }
}
