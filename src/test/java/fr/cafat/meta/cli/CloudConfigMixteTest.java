package fr.cafat.meta.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parc mixte analysé en une seule exécution : s-gen-aaa garde sa configuration locale (convention
 * {@code nc.cafat.rest-dependency}), s-gen-bbb est passée sous Spring Cloud Config (nom versionné,
 * profils fonctionnels par groupe, convention {@code cafat.config.rest-dependency} dans le dépôt de
 * configuration extrait sur la branche {@code dev}).
 */
class CloudConfigMixteTest {

  @TempDir
  Path depots;

  @TempDir
  Path configRepo;

  @TempDir
  Path out;

  private void write(Path root, String rel, String content) throws IOException {
    Path p = root.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, content, StandardCharsets.UTF_8);
  }

  private static String pom(String artifactId, String dependencies) {
    return """
        <project>
          <groupId>nc.cafat.gen</groupId>
          <artifactId>%s</artifactId>
          <version>1.0.0</version>
          <dependencies>%s</dependencies>
        </project>
        """.formatted(artifactId, dependencies);
  }

  /** Classe utilitaire CAFAT : URL = hôte(type) + service + version + chemin. */
  private static String urlConfig(String pkg, String prefix, String hostKey) {
    return """
        package %s;
        import org.springframework.core.env.Environment;
        @org.springframework.stereotype.Component
        public class URLConfig {
          private static final String PACKAGE_DEPENDENCY = "%s";
          private static Environment env;
          public URLConfig(Environment env) { URLConfig.env = env; }
          public static String getUrl(String serviceName, String pathName, String... values) {
            return String.format(getHost(serviceName) + getProperty(serviceName, "service")
                + getProperty(serviceName, "version") + getProperty(serviceName, "path." + pathName), values);
          }
          private static String getProperty(String serviceName, String key) {
            return env.getProperty(PACKAGE_DEPENDENCY + serviceName + "." + key);
          }
          private static String getHost(String serviceName) {
            String type = getProperty(serviceName, "type");
            return env.getProperty(PACKAGE_DEPENDENCY + %s);
          }
        }
        """.formatted(pkg, prefix, hostKey);
  }

  @BeforeEach
  void parc() throws IOException {
    // s-gen-aaa : configuration locale, ancienne convention
    Path a = depots.resolve("s-gen-aaa");
    write(a, "pom.xml", pom("s-gen-aaa", ""));
    write(a, "src/main/resources/application.yml", """
        spring.application.name: s-gen-aaa
        server.servlet.context-path: /s-gen-aaa-1.0
        nc.cafat:
          rest-dependency:
            springboot.host: https://api-dev
            s-gen-bbb:
              service: /s-gen-bbb-
              version: 2.0
              type: springboot
              path:
                lire: /lire/%s
        """);
    write(a, "src/main/java/nc/cafat/aaa/AaaApplication.java", """
        package nc.cafat.aaa;
        @org.springframework.boot.autoconfigure.SpringBootApplication
        public class AaaApplication {
        }
        """);
    write(a, "src/main/java/nc/cafat/aaa/URLConfig.java",
        urlConfig("nc.cafat.aaa", "nc.cafat.rest-dependency.", "type + \".host\""));
    write(a, "src/main/java/nc/cafat/aaa/BbbClient.java", """
        package nc.cafat.aaa;
        import org.springframework.web.client.RestTemplate;
        public class BbbClient {
          private final RestTemplate restTemplate = new RestTemplate();
          public Object lire(String id) {
            return restTemplate.getForObject(URLConfig.getUrl("s-gen-bbb", "lire", id), Object.class);
          }
        }
        """);

    // s-gen-bbb : cliente de Spring Cloud Config, profils fonctionnels déclarés par groupe
    Path b = depots.resolve("s-gen-bbb");
    write(b, "pom.xml", pom("s-gen-bbb", """
        <dependency>
          <groupId>org.springframework.cloud</groupId>
          <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        """));
    write(b, "src/main/resources/application.yml", """
        spring:
          application:
            name: s-gen-bbb-2.0
          profiles:
            active: application-modules
            group:
              application-modules:
                - rest-dependency
        """);
    write(b, "src/main/java/nc/cafat/bbb/BbbApplication.java", """
        package nc.cafat.bbb;
        @org.springframework.boot.autoconfigure.SpringBootApplication
        public class BbbApplication {
        }
        """);
    write(b, "src/main/java/nc/cafat/bbb/URLConfig.java",
        urlConfig("nc.cafat.bbb", "cafat.config.rest-dependency.", "\"host.\" + type"));
    write(b, "src/main/java/nc/cafat/bbb/Clients.java", """
        package nc.cafat.bbb;
        import org.springframework.web.client.RestTemplate;
        public class Clients {
          private final RestTemplate restTemplate = new RestTemplate();
          public Object lire(String id) {
            return restTemplate.getForObject(URLConfig.getUrl("s-gen-aaa", "lire", id), Object.class);
          }
          public Object compte() {
            return restTemplate.getForObject(URLConfig.getUrl("s-gen-iam", "assure.account"), Object.class);
          }
        }
        """);

    // dépôt de configuration, branche dev
    write(configRepo, ".git/HEAD", "ref: refs/heads/dev\n");
    write(configRepo, "application.yml", "server.servlet.context-path: /${spring.application.name}\n");
    write(configRepo, "application-rest-dependency.yml", """
        cafat:
          config:
            rest-dependency:
              host:
                springboot: https://api-dev
        """);
    write(configRepo, "s-gen-bbb-2.0-rest-dependency.yml", """
        cafat:
          config:
            rest-dependency:
              s-gen-aaa:
                service: /s-gen-aaa-
                version: 1.0
                type: springboot
                path:
                  lire: /lire/%s
                  inutilise: /inutilise
              s-gen-iam:
                service: /s-gen-iam-
                version: 5.1
                type: springboot
                path:
                  assure:
                    account: /assure/account
        """);
    write(configRepo, "s-gen-autre-1.0.yml", "server.port: 1\n");
  }

  private MainTest.Run extract(String environment) throws IOException {
    MainTest.Run run = MainTest.run("--repos-dir", depots.toString(), "--out", out.toString(),
        "--config-repo", configRepo.toString(), "--environment", environment);
    assertThat(run.exit()).as(run.err()).isIn(0, 1);
    assertThat(run.files()).containsOnlyKeys("s-gen-aaa.json", "s-gen-bbb.json");
    return run;
  }

  private static JsonNode json(MainTest.Run run, String file) throws IOException {
    return new ObjectMapper().readTree(run.files().get(file));
  }

  private static List<String> texts(JsonNode array, String field) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.get(field).asText()));
    return out;
  }

  private static List<String> codes(JsonNode result) {
    return texts(result.get("diagnostics"), "code");
  }

  @Test
  void applicationClassiqueEtApplicationMigreeDansLaMemeExecution() throws IOException {
    MainTest.Run run = extract("dev");

    JsonNode aaa = json(run, "s-gen-aaa.json");
    JsonNode appA = aaa.get("application");
    assertThat(appA.get("environment").asText()).isEqualTo("dev");
    assertThat(appA.get("cloud_config").get("client").asBoolean()).isFalse();
    assertThat(appA.get("cloud_config").get("status").asText()).isEqualTo("not_client");
    assertThat(texts(aaa.get("calls"), "target_app")).containsExactly("s-gen-bbb");
    assertThat(texts(aaa.get("calls"), "resolved_url")).containsExactly("https://api-dev/s-gen-bbb-2.0/lire/{arg1}");
    assertThat(texts(aaa.get("rest_dependencies"), "config_prefix")).containsExactly("nc.cafat.rest-dependency");

    // nom sans version, profils déclarés par l'application, configuration distante de la branche dev
    JsonNode bbb = json(run, "s-gen-bbb.json");
    JsonNode appB = bbb.get("application");
    assertThat(appB.get("name").asText()).isEqualTo("s-gen-bbb");
    assertThat(appB.get("context_path").asText()).isEqualTo("/s-gen-bbb-2.0");
    assertThat(appB.get("profiles").toString()).isEqualTo("[\"application-modules\",\"rest-dependency\"]");
    JsonNode cloud = appB.get("cloud_config");
    assertThat(cloud.get("client").asBoolean()).isTrue();
    assertThat(cloud.get("status").asText()).isEqualTo("applied");
    assertThat(cloud.get("label").asText()).isEqualTo("dev");
    assertThat(cloud.get("names").toString()).isEqualTo("[\"s-gen-bbb-2.0\"]");
    assertThat(cloud.get("files").toString())
        .isEqualTo("[\"application.yml\",\"application-rest-dependency.yml\",\"s-gen-bbb-2.0-rest-dependency.yml\"]");

    // nouvelle convention : hôte host.<type>, chemin imbriqué, cible sans version
    assertThat(texts(bbb.get("calls"), "target_app")).containsExactlyInAnyOrder("s-gen-aaa", "s-gen-iam");
    assertThat(texts(bbb.get("calls"), "resolved_url")).containsExactlyInAnyOrder(
        "https://api-dev/s-gen-aaa-1.0/lire/{arg1}", "https://api-dev/s-gen-iam-5.1/assure/account");
    JsonNode deps = bbb.get("rest_dependencies");
    assertThat(texts(deps, "name")).containsExactly("s-gen-aaa", "s-gen-iam");
    assertThat(texts(deps, "config_prefix")).containsOnly("cafat.config.rest-dependency");
    assertThat(texts(deps, "host")).containsOnly("https://api-dev");
    List<String> paths = new ArrayList<>();
    deps.forEach(d -> d.get("paths").forEach(p -> paths.add(d.get("name").asText() + ":" + p.get("name").asText()
        + "@" + p.get("called").asBoolean())));
    assertThat(paths).containsExactly("s-gen-aaa:inutilise@false", "s-gen-aaa:lire@true",
        "s-gen-iam:assure.account@true");

    assertThat(codes(aaa)).doesNotContain("URL_UNRESOLVED", "PROFILE_NOT_APPLIED");
    assertThat(codes(bbb)).doesNotContain("URL_UNRESOLVED", "PROFILE_NOT_APPLIED", "CLOUD_CONFIG_NOT_FOUND",
        "CLOUD_CONFIG_LABEL_MISMATCH");
  }

  @Test
  void brancheDuDepotDifferenteDeLEnvironnementCible() throws IOException {
    MainTest.Run run = extract("val");

    assertThat(codes(json(run, "s-gen-bbb.json"))).contains("CLOUD_CONFIG_LABEL_MISMATCH");
    assertThat(codes(json(run, "s-gen-aaa.json"))).doesNotContain("CLOUD_CONFIG_LABEL_MISMATCH");
    assertThat(json(run, "s-gen-bbb.json").get("application").get("environment").asText()).isEqualTo("val");
  }

  @Test
  void suffixeDeVersionRetireDuNom() {
    assertThat(Pipeline.withoutVersion("s-gen-geo-5.0")).isEqualTo("s-gen-geo");
    assertThat(Pipeline.withoutVersion("s-gen-geo-5.0.1")).isEqualTo("s-gen-geo");
    assertThat(Pipeline.withoutVersion("s-gen-geo")).isEqualTo("s-gen-geo");
    assertThat(Pipeline.withoutVersion("app-2")).isEqualTo("app-2");
  }
}
