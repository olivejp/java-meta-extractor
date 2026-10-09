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

class CloudConfigRepoTest {

  private static void write(Path root, String file, String text) throws IOException {
    Path f = root.resolve(file);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }

  private static Config local(String... keyValues) {
    Config c = new Config();
    for (int i = 0; i < keyValues.length; i += 2) {
      c.put(new ConfigEntry(keyValues[i], keyValues[i + 1], "application.yml", 1));
    }
    return c;
  }

  @Test
  void prioriteDesFichiersEtDuDistantSurLeLocal(@TempDir Path repo) throws IOException {
    write(repo, "application.yml", "a: commun\nb: commun\nc: commun\nd: commun\n");
    write(repo, "s-app.yml", "b: app\nc: app\nd: app\n");
    write(repo, "application-prod.yml", "c: commun-prod\nd: commun-prod\n");
    write(repo, "s-app-prod.properties", "d=app-prod\n");
    write(repo, "autre-app.yml", "a: autre\n");
    Config config = local("a", "local", "e", "local");
    Diagnostics diags = new Diagnostics();

    List<Path> applied = new CloudConfigRepo(repo, List.of()).apply(config, List.of("s-app"), List.of("prod"), diags);

    assertThat(applied).extracting(p -> p.getFileName().toString())
        .containsExactly("application.yml", "s-app.yml", "application-prod.yml", "s-app-prod.properties");
    assertThat(config.get("a")).isEqualTo("commun");
    assertThat(config.get("b")).isEqualTo("app");
    assertThat(config.get("c")).isEqualTo("commun-prod");
    assertThat(config.get("d")).isEqualTo("app-prod");
    assertThat(config.get("e")).isEqualTo("local");
    assertThat(config.entry("d").file()).isEqualTo("cloud-config:s-app-prod.properties");
    assertThat(diags.all()).isEmpty();
  }

  @Test
  void cleRelacheeRemplaceLaFormeLocale(@TempDir Path repo) throws IOException {
    write(repo, "s-app.yml", "api:\n  base-url: http://s-cible:8080\n");
    Config config = local("api.baseUrl", "${API_HOST}");

    new CloudConfigRepo(repo, List.of()).apply(config, List.of("s-app"), List.of(), new Diagnostics());

    assertThat(config.get("api.baseUrl")).isEqualTo("http://s-cible:8080");
  }

  @Test
  void repertoiresDeRechercheEtProfilParDefaut(@TempDir Path repo) throws IOException {
    write(repo, "s-app/s-app.yml", "x: dossier-app\n");
    write(repo, "commun/env/application-default.yml", "y: defaut\n");
    Config config = local();

    new CloudConfigRepo(repo, List.of("{application}", "commun/*"))
        .apply(config, List.of("s-app"), List.of(), new Diagnostics());

    assertThat(config.get("x")).isEqualTo("dossier-app");
    assertThat(config.get("y")).isEqualTo("defaut");
  }

  @Test
  void documentConditionneParProfil(@TempDir Path repo) throws IOException {
    write(repo, "s-app.yml", "u: base\n---\nspring:\n  config:\n    activate:\n      on-profile: prod\nu: prod\n");
    Config dev = local();
    Config prod = local();
    CloudConfigRepo cloud = new CloudConfigRepo(repo, List.of());

    cloud.apply(dev, List.of("s-app"), List.of("dev"), new Diagnostics());
    cloud.apply(prod, List.of("s-app"), List.of("prod"), new Diagnostics());

    assertThat(dev.get("u")).isEqualTo("base");
    assertThat(prod.get("u")).isEqualTo("prod");
  }

  @Test
  void valeurChiffreeMasquee(@TempDir Path repo) throws IOException {
    write(repo, "s-app.yml", "partenaire:\n  url: '{cipher}AQB3f9'\n");
    Config config = local();

    new CloudConfigRepo(repo, List.of()).apply(config, List.of("s-app"), List.of(), new Diagnostics());

    assertThat(config.get("partenaire.url")).isEqualTo(Secrets.MASK);
  }

  @Test
  void sansFichierPropreALApplication(@TempDir Path repo) throws IOException {
    write(repo, "application.yml", "a: commun\n");
    Diagnostics diags = new Diagnostics();

    new CloudConfigRepo(repo, List.of()).apply(local(), List.of("s-app"), List.of(), diags);

    assertThat(diags.all()).extracting(Diagnostic::code).containsExactly("CLOUD_CONFIG_NOT_FOUND");
  }

  @Test
  void detectionDuClientEtNoms() {
    assertThat(CloudConfigRepo.isClient(local(), List.of("<artifactId>spring-cloud-starter-config</artifactId>")))
        .isTrue();
    assertThat(CloudConfigRepo.isClient(local("spring.config.import", "optional:configserver:http://cfg"), List.of()))
        .isTrue();
    assertThat(CloudConfigRepo.isClient(local("spring.cloud.config.uri", "http://cfg"), List.of())).isTrue();
    assertThat(CloudConfigRepo.isClient(local("spring.cloud.config.enabled", "false"),
        List.of("spring-cloud-starter-config"))).isFalse();
    assertThat(CloudConfigRepo.isClient(local(), List.of("spring-boot-starter-web"))).isFalse();

    assertThat(CloudConfigRepo.names(local("spring.application.name", "s-app"), "artefact"))
        .containsExactly("s-app");
    assertThat(CloudConfigRepo.names(local("spring.application.name", "s-app", "spring.cloud.config.name",
        "commun, s-app"), "artefact")).containsExactly("commun", "s-app");
    assertThat(CloudConfigRepo.names(local(), "artefact")).containsExactly("artefact");
  }
}
