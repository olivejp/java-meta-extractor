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
                inutilise: /inutilise
            s-gen-etp:
              service: /s-gen-etp
              version: 6.0
              type: adelia
              path:
                rechercher: /rest/etapes/rechercher
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
          public Object etapes() {
            return b2bRestTemplate.getForObject(URLConfig.getUrl("s-gen-etp", "rechercher"), Object.class);
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

    // URL construites par URLConfig ; une URL reçue en paramètre est suivie jusqu'aux appelants ;
    // cible lue dans nc.cafat.rest-dependency, pas dans l'hôte (passerelle) ; hôte adelia absent sans
    // URL_UNRESOLVED
    assertThat(ids(result.get("calls"))).containsExactly(
        "s-gen-demo:GET:s-gen-etp:/s-gen-etp6.0/rest/etapes/rechercher@nc.cafat.demo.RefClient#etapes",
        "s-gen-demo:GET:s-gen-ref:/s-gen-ref-5.0/pays/{arg1}@nc.cafat.demo.RefClient#pays",
        "s-gen-demo:POST:s-gen-ref:/s-gen-ref-5.0/assure/confirm@nc.cafat.demo.RefClient#confirmer",
        "s-gen-demo:POST:s-gen-ref:/s-gen-ref-5.0/assure/{arg1}/check@nc.cafat.demo.RefClient#verifier");
    assertThat(result.get("calls").get(0).get("resolved_url").isNull()).isTrue();
    assertThat(result.get("calls").get(1).get("resolved_url").asText())
        .isEqualTo("http://s-gen-gateway/s-gen-ref-5.0/pays/{arg1}");

    // dépendances déclarées : hôtes par type exclus, chemins appelés ou non
    JsonNode deps = result.get("rest_dependencies");
    assertThat(ids(deps)).containsExactly("s-gen-demo:rest-dependency:s-gen-etp",
        "s-gen-demo:rest-dependency:s-gen-ref");
    JsonNode etp = deps.get(0);
    assertThat(etp.get("target_app").asText()).isEqualTo("s-gen-etp");
    assertThat(etp.get("type").asText()).isEqualTo("adelia");
    assertThat(etp.get("base_path").asText()).isEqualTo("/s-gen-etp6.0");
    assertThat(etp.get("host").isNull()).isTrue();
    assertThat(etp.get("called").asBoolean()).isTrue();
    JsonNode ref = deps.get(1);
    assertThat(ref.get("host").asText()).isEqualTo("http://s-gen-gateway");
    assertThat(ref.get("source").get("file").asText()).isEqualTo("src/main/resources/application.yml");
    List<String> paths = new ArrayList<>();
    ref.get("paths").forEach(p -> paths.add(p.get("name").asText() + "=" + p.get("path").asText() + "@"
        + p.get("called").asBoolean()));
    assertThat(paths).containsExactly("check=/s-gen-ref-5.0/assure/{arg1}/check@true",
        "confirm=/s-gen-ref-5.0/assure/confirm@true", "inutilise=/s-gen-ref-5.0/inutilise@false",
        "pays=/s-gen-ref-5.0/pays/{arg1}@true");
  }

  /**
   * Variante de s-gen-cli : URLConfig est un bean qui lit les dépendances dans une Map de
   * {@code @ConfigurationProperties} (pas dans l'Environment) ; l'hôte n'est que dans un profil.
   */
  @Test
  void dependancesLuesDansUneMapDeConfigurationProperties() throws IOException {
    write("pom.xml", """
        <project>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>2.7.18</version>
          </parent>
          <groupId>nc.cafat.gen</groupId>
          <artifactId>s-gen-demo</artifactId>
          <version>2.3.1</version>
        </project>
        """);
    write("src/main/resources/application.yml", """
        nc.cafat:
          rest-dependency:
            s-gen-gpp:
              service: /s-gen-gpp-
              version: 3.5
              type: springboot
              path:
                personne-physique.nir: /personne-physique/{matricule}/nir
                personne-physique.assure.contact: /personne-physique/{matricule}/contact
                adresse-bp: /adresse-bp/{id}
                adresse-domicile: /adresse-domicile/{id}
                adresse-domicile-smart: /adresse-domicile/smart
        """);
    write("src/main/resources/application-local.yml", """
        nc.cafat.rest-dependency.springboot.host: https://api-dev
        """);
    write("src/main/java/nc/cafat/demo/DemoApplication.java", """
        package nc.cafat.demo;
        @org.springframework.boot.autoconfigure.SpringBootApplication
        public class DemoApplication {
        }
        """);
    write("src/main/java/nc/cafat/demo/RestDependency.java", """
        package nc.cafat.demo;
        import java.util.Map;
        @lombok.Data
        public class RestDependency {
          public enum RestDependencyType { springboot, adelia }
          private String host;
          private String service;
          private String version;
          private RestDependencyType type;
          private Map<String, String> path;
        }
        """);
    write("src/main/java/nc/cafat/demo/RestDependencyConfig.java", """
        package nc.cafat.demo;
        import java.util.*;
        import org.springframework.boot.context.properties.ConfigurationProperties;
        @ConfigurationProperties(prefix = "nc.cafat")
        public class RestDependencyConfig {
          @lombok.Getter
          private final Map<String, RestDependency> restDependency = new HashMap<>();
          private final EnumMap<RestDependency.RestDependencyType, String> hosts =
              new EnumMap<>(RestDependency.RestDependencyType.class);
          public Optional<RestDependency> getRestDependency(String name) {
            return Optional.ofNullable(this.restDependency.get(name));
          }
          public Optional<String> getHost(RestDependency.RestDependencyType type) {
            return Optional.ofNullable(this.hosts.get(type));
          }
        }
        """);
    write("src/main/java/nc/cafat/demo/URLConfig.java", """
        package nc.cafat.demo;
        import java.util.Optional;
        @org.springframework.context.annotation.Configuration
        public class URLConfig {
          private final RestDependencyConfig restDependencyConfig;
          public URLConfig(RestDependencyConfig restDependencyConfig) {
            this.restDependencyConfig = restDependencyConfig;
          }
          private static String getPath(RestDependency restDependency, String serviceName, String pathName) {
            return restDependency.getPath().get(pathName);
          }
          public String getUrl(String serviceName, String pathName) {
            Optional<RestDependency> restDependency = this.restDependencyConfig.getRestDependency(serviceName);
            return getUrl(restDependency.get()) + getPath(restDependency.get(), serviceName, pathName);
          }
          private String getUrl(RestDependency restDependency) {
            return getHost(restDependency.getType()) + restDependency.getService() + restDependency.getVersion();
          }
          private String getHost(RestDependency.RestDependencyType type) {
            return this.restDependencyConfig.getHost(type).get();
          }
        }
        """);
    write("src/main/java/nc/cafat/demo/GPPRestClient.java", """
        package nc.cafat.demo;
        import java.net.URI;
        import org.springframework.web.client.RestTemplate;
        import org.springframework.web.util.UriComponentsBuilder;
        @org.springframework.stereotype.Component
        public class GPPRestClient {
          public static final String SERVICE_NAME = "s-gen-gpp";
          private static final String KEY_CONTACT = "personne-physique.assure.contact";
          public static final String KEY_NIR = "personne-physique.nir";
          private final RestTemplate b2bRestTemplate;
          private final URLConfig urlConfig;
          public GPPRestClient(RestTemplate b2bRestTemplate, URLConfig urlConfig) {
            this.b2bRestTemplate = b2bRestTemplate;
            this.urlConfig = urlConfig;
          }
          public Object getContact(Integer matricule, String type) {
            URI uri = UriComponentsBuilder.fromUriString(this.urlConfig.getUrl(SERVICE_NAME, KEY_CONTACT))
                .queryParam("type", "{typeContact}")
                .buildAndExpand(matricule, type)
                .toUri();
            return b2bRestTemplate.getForObject(uri, Object.class);
          }
          public void updateNir(Integer matricule, Object nir) {
            URI uri = UriComponentsBuilder.fromUriString(this.urlConfig.getUrl(SERVICE_NAME, KEY_NIR))
                .buildAndExpand(matricule).toUri();
            b2bRestTemplate.put(uri, nir);
          }
          public Object inconnu() {
            return b2bRestTemplate.getForObject(this.urlConfig.getUrl(SERVICE_NAME, "absent"), Object.class);
          }
          public Object adresse(Long id, java.time.DayOfWeek type) {
            String cle = "adresse-" + type.name().toLowerCase();
            return b2bRestTemplate.getForObject(this.urlConfig.getUrl(SERVICE_NAME, cle), Object.class, id);
          }
          public Object calculee(java.util.function.Supplier<String> s) {
            return b2bRestTemplate.getForObject(this.urlConfig.getUrl(SERVICE_NAME, s.get()), Object.class);
          }
        }
        """);

    JsonNode result = extract();

    // cible et chemin lus dans nc.cafat.rest-dependency ; hôte (profil local) absent sans URL_UNRESOLVED ;
    // chemin non déclaré : cible gardée, chemin inconnu ; clé calculée : un appel par chemin compatible
    String base = "${nc.cafat.rest-dependency.springboot.host}${nc.cafat.rest-dependency.s-gen-gpp.service}"
        + "${nc.cafat.rest-dependency.s-gen-gpp.version}${nc.cafat.rest-dependency.s-gen-gpp.path.";
    assertThat(ids(result.get("calls"))).containsExactly(
        "s-gen-demo:GET:s-gen-gpp:" + base + "absent}@nc.cafat.demo.GPPRestClient#inconnu",
        "s-gen-demo:GET:s-gen-gpp:" + base + "{get}}@nc.cafat.demo.GPPRestClient#calculee",
        "s-gen-demo:GET:s-gen-gpp:/s-gen-gpp-3.5/adresse-bp/{id}@nc.cafat.demo.GPPRestClient#adresse",
        "s-gen-demo:GET:s-gen-gpp:/s-gen-gpp-3.5/adresse-domicile/{id}@nc.cafat.demo.GPPRestClient#adresse",
        "s-gen-demo:GET:s-gen-gpp:/s-gen-gpp-3.5/personne-physique/{matricule}/contact"
            + "@nc.cafat.demo.GPPRestClient#getContact",
        "s-gen-demo:PUT:s-gen-gpp:/s-gen-gpp-3.5/personne-physique/{matricule}/nir"
            + "@nc.cafat.demo.GPPRestClient#updateNir");
    JsonNode inconnu = result.get("calls").get(0);
    assertThat(inconnu.get("path").isNull()).isTrue();
    assertThat(inconnu.get("target_app").asText()).isEqualTo("s-gen-gpp");
    JsonNode contact = result.get("calls").get(4);
    assertThat(contact.get("raw_url").asText()).isEqualTo("${nc.cafat.rest-dependency.springboot.host}"
        + "${nc.cafat.rest-dependency.s-gen-gpp.service}${nc.cafat.rest-dependency.s-gen-gpp.version}"
        + "${nc.cafat.rest-dependency.s-gen-gpp.path.personne-physique.assure.contact}");
    assertThat(contact.get("resolved_url").isNull()).isTrue();

    // chemin non déclaré et clé sans morceau connu : non résolus ; clé partielle : chemins possibles
    List<String> diags = new ArrayList<>();
    result.get("diagnostics").forEach(d -> {
      if (d.get("code").asText().startsWith("URL_")) {
        diags.add(d.get("code").asText() + "@" + d.get("source").get("line").asText());
      }
    });
    assertThat(diags).containsExactlyInAnyOrder("URL_UNRESOLVED@29", "URL_UNRESOLVED@36", "URL_ALTERNATIVES@33");

    JsonNode gpp = result.get("rest_dependencies").get(0);
    assertThat(gpp.get("called").asBoolean()).isTrue();
    List<String> paths = new ArrayList<>();
    gpp.get("paths").forEach(p -> paths.add(p.get("name").asText() + "@" + p.get("called").asBoolean()));
    assertThat(paths).containsExactly("adresse-bp@true", "adresse-domicile@true", "adresse-domicile-smart@false",
        "personne-physique.assure.contact@true", "personne-physique.nir@true");
  }

  /**
   * Persistance et JMS de s-gen-cli : schéma PostgreSQL fourni par un profil (déduit du SQL Jdbi),
   * bean Jdbi d'une bibliothèque, fabrique d'EntityManager déclarée à la main (noms Hibernate par
   * défaut, ramenés à la casse PostgreSQL), file lue dans un bean {@code @ConfigurationProperties}
   * (getter et SpEL), faute {@code librairies} de l'URL AS400.
   */
  @Test
  void persistanceEtJmsFaconSGenCli() throws IOException {
    write("pom.xml", """
        <project>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>2.7.18</version>
          </parent>
          <groupId>nc.cafat.gen</groupId>
          <artifactId>s-gen-demo</artifactId>
          <version>1.0.0</version>
        </project>
        """);
    write("src/main/resources/application.yml", """
        spring:
          postgresql:
            datasource:
              driver-class-name: org.postgresql.Driver
              jdbcUrl: ${spring.datasource.url}
            jpa.properties.default_schema: ${postgresql.schema.sgendemo}
          as400:
            datasource:
              jdbcUrl: jdbc:as400://h;librairies=MDEMO${as400.env-suffix:};prompt=false
        nc.cafat.demo:
          private-queue:
            name: QDEMO
        """);
    write("src/main/java/nc/cafat/demo/DemoApplication.java", """
        package nc.cafat.demo;
        @org.springframework.boot.autoconfigure.SpringBootApplication
        public class DemoApplication {
        }
        """);
    write("src/main/java/nc/cafat/demo/PersistencePg.java", """
        package nc.cafat.demo;
        import javax.sql.DataSource;
        import org.springframework.boot.context.properties.ConfigurationProperties;
        import org.springframework.context.annotation.*;
        import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
        import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
        @Configuration
        public class PersistencePg {
          @Primary @Bean @ConfigurationProperties(prefix = "spring.postgresql.datasource")
          public DataSource postgresqlDataSource() { return null; }
          @Primary @Bean
          public LocalContainerEntityManagerFactoryBean pgEntityManagerFactory() {
            LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
            em.setDataSource(postgresqlDataSource());
            em.setPackagesToScan("nc.cafat.demo.pg");
            em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            return em;
          }
        }
        """);
    write("src/main/java/nc/cafat/demo/pg/Client.java", """
        package nc.cafat.demo.pg;
        import javax.persistence.*;
        @Entity
        @Table(name = "DEMO_CLIENT")
        public class Client {
          @Id @Column(name = "ID") private Long id;
          private java.util.Date dateCreation;
        }
        """);
    write("src/main/java/nc/cafat/demo/sync/ClientDao.java", """
        package nc.cafat.demo.sync;
        public interface ClientDao {
          @org.jdbi.v3.sqlobject.statement.SqlQuery("select id from sgendemo.demo_client")
          java.util.List<Long> ids();
        }
        """);
    write("src/main/java/nc/cafat/demo/sync/DaoConfig.java", """
        package nc.cafat.demo.sync;
        import org.jdbi.v3.core.Jdbi;
        @org.springframework.context.annotation.Configuration
        public class DaoConfig {
          @org.springframework.context.annotation.Bean
          public ClientDao clientDao(Jdbi postgresqlJdbi) { return postgresqlJdbi.onDemand(ClientDao.class); }
        }
        """);
    write("src/main/java/nc/cafat/demo/Config.java", """
        package nc.cafat.demo;
        @lombok.Data
        @org.springframework.context.annotation.Configuration
        @org.springframework.boot.context.properties.ConfigurationProperties("nc.cafat.demo")
        public class Config {
          private PrivateQueueConfig privateQueue;
          @lombok.Data
          public static class PrivateQueueConfig {
            private String name;
          }
        }
        """);
    write("src/main/java/nc/cafat/demo/AmqConfig.java", """
        package nc.cafat.demo;
        import javax.jms.Queue;
        import org.apache.activemq.command.ActiveMQQueue;
        @org.springframework.context.annotation.Configuration
        public class AmqConfig {
          @org.springframework.context.annotation.Bean
          public Queue privateQueue(Config config) { return new ActiveMQQueue(config.getPrivateQueue().getName()); }
        }
        """);
    write("src/main/java/nc/cafat/demo/MajProducer.java", """
        package nc.cafat.demo;
        import javax.jms.Queue;
        import org.springframework.jms.core.JmsTemplate;
        public class MajProducer {
          private final JmsTemplate jmsTemplate;
          private final Queue privateQueue;
          public MajProducer(JmsTemplate jmsTemplate, Queue privateQueue) {
            this.jmsTemplate = jmsTemplate;
            this.privateQueue = privateQueue;
          }
          public void envoyer(String json) { jmsTemplate.convertAndSend(privateQueue, json); }
        }
        """);
    write("src/main/java/nc/cafat/demo/MajListener.java", """
        package nc.cafat.demo;
        public class MajListener {
          @org.springframework.jms.annotation.JmsListener(destination = "#{config.privateQueue.name}")
          public void recevoir(String json) { }
        }
        """);

    JsonNode result = extract();
    assertThat(codes(result)).contains("DEFAULT_SCHEMA_INFERRED")
        .doesNotContain("DATASOURCE_AMBIGUOUS", "DESTINATION_UNRESOLVED");

    // AS400 : bibliothèque lue malgré la faute librairies= ; PostgreSQL : schéma de configuration inconnu
    List<String> schemas = new ArrayList<>();
    result.get("application").get("datasources")
        .forEach(d -> schemas.add(d.get("id").asText() + "=" + d.get("default_schema").asText()));
    assertThat(schemas).containsExactly("as400=MDEMO", "postgresql=null");

    // fabrique déclarée à la main : noms tels qu'écrits, implicites JPA, puis casse PostgreSQL ;
    // schéma déduit du SQL Jdbi (bean postgresqlJdbi d'une bibliothèque → postgresqlDataSource)
    JsonNode client = result.get("entities").get(0);
    assertThat(client.get("datasource").asText()).isEqualTo("postgresql");
    assertThat(client.get("schema").asText() + "." + client.get("table").asText()).isEqualTo("sgendemo.demo_client");
    List<String> columns = new ArrayList<>();
    client.get("columns").forEach(c -> columns.add(c.get("name").asText()));
    assertThat(columns).containsExactlyInAnyOrder("id", "datecreation");
    JsonNode sql = result.get("sql_accesses").get(0);
    assertThat(sql.get("datasource").asText()).isEqualTo("postgresql");

    // file lue dans le bean @ConfigurationProperties, par getter et par SpEL
    List<String> destinations = new ArrayList<>();
    result.get("messaging").forEach(m -> destinations.add(m.get("role").asText() + "=" + m.get("destination").asText()));
    assertThat(destinations).containsExactlyInAnyOrder("consume=QDEMO", "produce=QDEMO");
  }
}
