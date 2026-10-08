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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Conventions des services Spring Boot de la CAFAT (relevées sur s-gen-gpp) : application.yml filtré
 * par Maven, sources de données nommées, {@code {h-schema}}, file JMS injectée par bean, URL des
 * dépendances REST construites par une classe utilitaire à partir de la configuration.
 */
class ConventionsCafatTest {

  @TempDir
  Path repo;

  @TempDir
  Path out;

  private void write(String rel, String content) throws IOException {
    Path p = repo.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, content, StandardCharsets.UTF_8);
  }

  private JsonNode extract() throws IOException {
    MainTest.Run run = MainTest.run("--repo", repo.toString(), "--commit", "abc1234", "--out", out.toString());
    assertThat(run.exit()).as(run.err()).isIn(0, 1);
    assertThat(run.files()).containsOnlyKeys("s-gen-demo.json");
    return new ObjectMapper().readTree(run.files().get("s-gen-demo.json"));
  }

  private static List<String> ids(JsonNode array) {
    List<String> ids = new ArrayList<>();
    array.forEach(n -> ids.add(n.get("id").asText()));
    return ids;
  }

  private static List<String> codes(JsonNode result) {
    List<String> codes = new ArrayList<>();
    result.get("diagnostics").forEach(d -> codes.add(d.get("code").asText()));
    return codes;
  }

  @Test
  void serviceSpringBootCafat() throws IOException {
    write("pom.xml", """
        <project>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>2.7.18</version>
          </parent>
          <groupId>nc.cafat.gen</groupId>
          <artifactId>s-gen-demo</artifactId>
          <version>3.5.10</version>
          <description>Service de démonstration</description>
          <build>
            <resources>
              <resource><directory>src/main/resources</directory><filtering>true</filtering></resource>
            </resources>
            <plugins>
              <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
              <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <executions><execution><id>parse-version</id><goals><goal>parse-version</goal></goals></execution></executions>
              </plugin>
            </plugins>
          </build>
        </project>
        """);
    write("src/main/resources/application.yml", """
        build:
          artifact: @artifactId@
          description: @project.description@
          version: @parsedVersion.majorVersion@.@parsedVersion.minorVersion@
          inconnu: a@absent@b
        server:
          servlet:
            context-path: /${build.artifact}-${build.version}
        spring:
          application.name: ${build.artifact}
          postgresql:
            datasource:
              driver-class-name: org.postgresql.Driver
              jdbcUrl: ${spring.datasource.url}
            jpa:
              database-platform: org.hibernate.dialect.PostgreSQLDialect
          as400:
            datasource:
              jdbcUrl: jdbc:as400://${as400.server-name};libraries=MDEMO;prompt=false
        nc.cafat:
          rest-dependency:
            springboot.host: http://s-gen-gateway
            s-gen-ref:
              service: /s-gen-ref-
              version: 5.0
              type: springboot
              path:
                pays: /pays/%s
                confirm: /assure/confirm
                check: /assure/%d/check
          demo.queue: QDEMO
        """);
    write("src/main/java/nc/cafat/demo/DemoApplication.java", """
        package nc.cafat.demo;
        @org.springframework.boot.autoconfigure.SpringBootApplication
        public class DemoApplication {
        }
        """);
    write("src/main/java/nc/cafat/demo/DemoController.java", """
        package nc.cafat.demo;
        import org.springframework.web.bind.annotation.*;
        @RestController
        @RequestMapping("/demo")
        public class DemoController {
          @GetMapping
          public String lire() { return "ok"; }
        }
        """);
    write("src/main/java/nc/cafat/demo/PersonneRepository.java", """
        package nc.cafat.demo;
        import org.springframework.data.jpa.repository.*;
        public interface PersonneRepository extends JpaRepository<Object, Long> {
          @Query(nativeQuery = true, value = "select p.* from {h-schema}demo_personne as p where {h-schema}fts(p, :q) = true")
          java.util.List<Object> rechercher(String q);
        }
        """);
    write("src/main/java/nc/cafat/demo/URLConfig.java", """
        package nc.cafat.demo;
        import org.springframework.core.env.Environment;
        import org.springframework.web.util.UriComponentsBuilder;
        @org.springframework.stereotype.Component
        public class URLConfig {
          private static final String PACKAGE_DEPENDENCY = "nc.cafat.rest-dependency.";
          private static Environment env;
          public URLConfig(Environment env) { URLConfig.env = env; }
          public static String getUrl(String serviceName) {
            return getHost(serviceName) + getService(serviceName) + getVersion(serviceName);
          }
          public static String getUrl(String serviceName, String pathName) {
            return getUrl(serviceName) + getPath(serviceName, pathName);
          }
          public static String getUrl(String serviceName, String pathName, String... values) {
            return String.format(getUrl(serviceName, pathName), values);
          }
          public static UriComponentsBuilder getUriBuilder(String serviceName, String pathName, String... values) {
            return UriComponentsBuilder.fromUriString(URLConfig.getUrl(serviceName, pathName, values));
          }
          private static String getService(String serviceName) {
            return env.getProperty(PACKAGE_DEPENDENCY + serviceName + ".service");
          }
          private static String getType(String serviceName) {
            return env.getProperty(PACKAGE_DEPENDENCY + serviceName + ".type");
          }
          private static String getVersion(String serviceName) {
            return env.getProperty(PACKAGE_DEPENDENCY + serviceName + ".version");
          }
          private static String getHost(String serviceName) {
            return env.getProperty(PACKAGE_DEPENDENCY + getType(serviceName) + ".host");
          }
          private static String getPath(String serviceName, String pathName) {
            return env.getProperty(PACKAGE_DEPENDENCY + serviceName + ".path." + pathName);
          }
        }
        """);
    write("src/main/java/nc/cafat/demo/RefClient.java", """
        package nc.cafat.demo;
        import java.net.URI;
        import java.util.Map;
        import org.springframework.http.HttpMethod;
        import org.springframework.web.client.RestTemplate;
        import org.springframework.web.util.UriComponentsBuilder;
        public class RefClient {
          private final RestTemplate b2bRestTemplate;
          public RefClient(RestTemplate b2bRestTemplate) { this.b2bRestTemplate = b2bRestTemplate; }
          public Object pays(String code) {
            String url = URLConfig.getUriBuilder("s-gen-ref", "pays", code).toUriString();
            return b2bRestTemplate.getForObject(url, Object.class);
          }
          public void confirmer() {
            post(UriComponentsBuilder.fromUriString(URLConfig.getUrl("s-gen-ref", "confirm")).toUriString(), Map.of());
          }
          public void verifier(Integer matricule) {
            post(String.format(URLConfig.getUrl("s-gen-ref", "check"), matricule), Map.of());
          }
          private void post(String url, Map<String, String> params) {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(url);
            URI uri = uriBuilder.build(params);
            b2bRestTemplate.exchange(uri, HttpMethod.POST, null, Void.class);
          }
        }
        """);
    write("src/main/java/nc/cafat/demo/AmqConfig.java", """
        package nc.cafat.demo;
        import javax.jms.Queue;
        import org.apache.activemq.command.ActiveMQQueue;
        import org.springframework.beans.factory.annotation.Value;
        import org.springframework.context.annotation.*;
        @Configuration
        public class AmqConfig {
          @Value("${nc.cafat.demo.queue}")
          private String demoQueue;
          @Bean
          public Queue demoQueue() { return new ActiveMQQueue(demoQueue); }
          @Bean
          public Queue autreQueue() { return new ActiveMQQueue("QAUTRE"); }
        }
        """);
    write("src/main/java/nc/cafat/demo/TaskProducer.java", """
        package nc.cafat.demo;
        import javax.jms.Queue;
        import org.springframework.jms.core.JmsTemplate;
        public class TaskProducer {
          private final JmsTemplate jmsTemplate;
          private final Queue demoQueue;
          public TaskProducer(JmsTemplate jmsTemplate, Queue demoQueue) {
            this.jmsTemplate = jmsTemplate;
            this.demoQueue = demoQueue;
          }
          public void post(String json) { jmsTemplate.convertAndSend(demoQueue, json); }
        }
        """);

    JsonNode result = extract();
    assertThat(codes(result)).doesNotContain("CONFIG_PARSE_ERROR", "URL_UNRESOLVED", "DESTINATION_UNRESOLVED",
        "SQL_UNPARSED");

    // filtrage Maven : nom, version courte, contexte
    JsonNode app = result.get("application");
    assertThat(app.get("name").asText()).isEqualTo("s-gen-demo");
    assertThat(app.get("context_path").asText()).isEqualTo("/s-gen-demo-3.5");
    assertThat(ids(result.get("endpoints"))).containsExactly("s-gen-demo:GET:/s-gen-demo-3.5/demo");

    // type de base lu dans le pilote quand l'URL vient de l'extérieur
    List<String> kinds = new ArrayList<>();
    app.get("datasources").forEach(d -> kinds.add(d.get("id").asText() + "=" + d.get("kind").asText()));
    assertThat(kinds).containsExactly("as400=db2", "postgresql=postgresql");

    // {h-schema} retiré avant l'analyse SQL
    JsonNode sql = result.get("sql_accesses").get(0);
    assertThat(sql.get("tables").get(0).get("name").asText()).isEqualTo("demo_personne");

    // file JMS injectée par nom de bean
    assertThat(ids(result.get("messaging")))
        .containsExactly("s-gen-demo:produce:queue:QDEMO@nc.cafat.demo.TaskProducer#post");

    // URL construites par URLConfig ; une URL reçue en paramètre est suivie jusqu'aux appelants
    assertThat(ids(result.get("calls"))).containsExactly(
        "s-gen-demo:GET:s-gen-gateway:/s-gen-ref-5.0/pays/{arg1}@nc.cafat.demo.RefClient#pays",
        "s-gen-demo:POST:s-gen-gateway:/s-gen-ref-5.0/assure/confirm@nc.cafat.demo.RefClient#confirmer",
        "s-gen-demo:POST:s-gen-gateway:/s-gen-ref-5.0/assure/{arg1}/check@nc.cafat.demo.RefClient#verifier");
    assertThat(result.get("calls").get(0).get("resolved_url").asText())
        .isEqualTo("http://s-gen-gateway/s-gen-ref-5.0/pays/{arg1}");
  }
}
