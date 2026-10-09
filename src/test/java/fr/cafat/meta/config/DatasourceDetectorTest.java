package fr.cafat.meta.config;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.config.DatasourceDetector.Detected;
import fr.cafat.meta.model.Datasource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatasourceDetectorTest {

  private static Config config(String... keyValues) {
    Config c = new Config();
    for (int i = 0; i < keyValues.length; i += 2) {
      c.put(new ConfigEntry(keyValues[i], keyValues[i + 1], "application.yml", i / 2 + 1));
    }
    return c;
  }

  private static List<Datasource> detect(Config c) {
    return DatasourceDetector.detect(c, List.of()).stream().map(Detected::datasource).toList();
  }

  private static Datasource byId(List<Datasource> all, String id) {
    return all.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
  }

  private static PersistenceUnit unit(String name, String jta, String nonJta, Map<String, String> props) {
    return new PersistenceUnit(name, jta, nonJta, List.of(), false, props, "META-INF/persistence.xml", 4,
        Path.of("."));
  }

  @Test
  void sourceSpringParDefaut() {
    List<Detected> all = DatasourceDetector.detect(config(
        "spring.datasource.url", "jdbc:postgresql://pg:5432/gpp?currentSchema=sgengpp,public",
        "spring.datasource.username", "u"), List.of());

    assertThat(all).singleElement().satisfies(d -> {
      assertThat(d.configPrefix()).isEqualTo("spring.datasource");
      assertThat(d.unit()).isNull();
      assertThat(d.datasource().id()).isEqualTo("default");
      assertThat(d.datasource().kind()).isEqualTo("postgresql");
      assertThat(d.datasource().defaultSchema()).isEqualTo("sgengpp");
      assertThat(d.datasource().configPrefix()).isEqualTo("spring.datasource");
      assertThat(d.datasource().source().file()).isEqualTo("application.yml");
      assertThat(d.datasource().source().line()).isEqualTo(1);
    });
  }

  @Test
  void plusieursSourcesTrieesParPrefixeEtSegmentsDePoolRetires() {
    List<Datasource> all = detect(config(
        "spring.datasource.pg.hikari.jdbc-url", "jdbc:postgresql://pg/gpp",
        "spring.datasource.db400.jdbc-url", "jdbc:as400://as400;libraries=MGENGPP,CAFGEO;naming=system",
        "app.ora.jdbcUrl", "jdbc:oracle:thin:@ora:1521/x"));

    assertThat(all).extracting(Datasource::id).containsExactly("ora", "db400", "pg");
    assertThat(byId(all, "pg").configPrefix()).isEqualTo("spring.datasource.pg");
    assertThat(byId(all, "db400").kind()).isEqualTo("db2");
    assertThat(byId(all, "db400").defaultSchema()).isEqualTo("MGENGPP");
    assertThat(byId(all, "ora").kind()).isEqualTo("other");
  }

  @Test
  void prefixesExclusEtUrlNonJdbcIgnores() {
    List<Datasource> all = detect(config(
        "spring.flyway.url", "jdbc:postgresql://pg/gpp",
        "spring.liquibase.url", "jdbc:postgresql://pg/gpp",
        "management.datasource.url", "jdbc:postgresql://pg/gpp",
        "referentiel.url", "http://s-ref:8080",
        "url", "jdbc:postgresql://sans-prefixe"));

    assertThat(all).isEmpty();
  }

  @Test
  void idAmbiguEnPrefixeComplet() {
    List<Datasource> all = detect(config(
        "a.datasource.url", "jdbc:postgresql://a/x",
        "b.a.db.url", "jdbc:postgresql://b/x"));

    assertThat(all).extracting(Datasource::id).containsExactly("a.datasource", "b.a.db");
  }

  @Test
  void urlAvecEspaceReserveDansUnPrefixeDatasource() {
    List<Datasource> all = detect(config(
        "spring.datasource.url", "${DB_URL}",
        "autre.url", "${AUTRE_URL}"));

    assertThat(all).singleElement().satisfies(d -> {
      assertThat(d.id()).isEqualTo("default");
      assertThat(d.jdbcUrl()).isEqualTo("${DB_URL}");
      assertThat(d.kind()).isNull();
    });
  }

  @Test
  void typeParLePiloteOuLeDialecteDuPrefixeJpaVoisin() {
    List<Datasource> all = detect(config(
        "spring.pg.datasource.url", "${PG}",
        "spring.pg.datasource.driver-class-name", "org.postgresql.Driver",
        "spring.db2.datasource.url", "${DB2}",
        "spring.db2.jpa.database-platform", "org.hibernate.dialect.DB2400Dialect",
        "spring.db2.jpa.properties.hibernate.default_schema", "MGENGPP"));

    assertThat(byId(all, "pg").kind()).isEqualTo("postgresql");
    assertThat(byId(all, "db2").kind()).isEqualTo("db2");
    assertThat(byId(all, "db2").defaultSchema()).isEqualTo("MGENGPP");
  }

  @Test
  void schemaDeclarePrioritaireSurLUrl() {
    Datasource d = detect(config(
        "spring.datasource.url", "jdbc:postgresql://pg/gpp?currentSchema=dans_url",
        "spring.jpa.properties.hibernate.default_schema", "declare")).get(0);

    assertThat(d.defaultSchema()).isEqualTo("declare");
  }

  @Test
  void sourceJndi() {
    Datasource d = detect(config("spring.datasource.jndi-name", "java:jboss/datasources/GppDS")).get(0);

    assertThat(d.id()).isEqualTo("default");
    assertThat(d.jndiName()).isEqualTo("java:jboss/datasources/GppDS");
    assertThat(d.jdbcUrl()).isNull();
    assertThat(d.kind()).isNull();
  }

  @Test
  void urlNettoyeeDesSecrets() {
    Datasource d = detect(config("spring.datasource.url",
        "jdbc:as400://h;libraries=LIB;user=GPPUSR;password=Secret-1")).get(0);

    assertThat(d.jdbcUrl()).doesNotContain("Secret-1").doesNotContain("GPPUSR").contains("password=***");
  }

  @Test
  void unitesDePersistance() {
    List<Detected> all = DatasourceDetector.detect(Config.empty(), List.of(
        unit("gppPU", "java:jboss/datasources/GppDS", null,
            Map.of("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.default_schema", "sgengpp")),
        unit("", null, "java:/LocalDS",
            Map.of("javax.persistence.jdbc.url", "jdbc:db2://h:50000/X:currentSchema=LIB;password=p"))));

    assertThat(all).hasSize(2);
    Datasource jta = all.get(0).datasource();
    assertThat(jta.id()).isEqualTo("gppPU");
    assertThat(jta.jndiName()).isEqualTo("java:jboss/datasources/GppDS");
    assertThat(jta.kind()).isEqualTo("postgresql");
    assertThat(jta.defaultSchema()).isEqualTo("sgengpp");
    assertThat(jta.configPrefix()).isNull();
    assertThat(jta.source().line()).isEqualTo(4);
    assertThat(all.get(0).unit().name()).isEqualTo("gppPU");

    Datasource local = all.get(1).datasource();
    assertThat(local.id()).isEqualTo("default");
    assertThat(local.jndiName()).isEqualTo("java:/LocalDS");
    assertThat(local.kind()).isEqualTo("db2");
    assertThat(local.defaultSchema()).isEqualTo("LIB");
    assertThat(local.jdbcUrl()).doesNotContain("=p");
  }

  @Test
  void typeDeBase() {
    assertThat(DatasourceDetector.kindOf("jdbc:pgsql://h/x", null)).isEqualTo("postgresql");
    assertThat(DatasourceDetector.kindOf("jdbc:db2://h/x", null)).isEqualTo("db2");
    assertThat(DatasourceDetector.kindOf("jdbc:h2:mem:x", "PostgreSQLDialect")).isEqualTo("other");
    assertThat(DatasourceDetector.kindOf("${URL}", "com.ibm.as400.access.AS400JDBCDriver")).isEqualTo("db2");
    assertThat(DatasourceDetector.kindOf(null, "org.h2.Driver")).isEqualTo("other");
    assertThat(DatasourceDetector.kindOf(null, null)).isNull();
  }

  @Test
  void schemaDansLUrl() {
    assertThat(DatasourceDetector.schemaFromUrl("jdbc:postgresql://h/x?ssl=true&currentSchema=s1")).isEqualTo("s1");
    assertThat(DatasourceDetector.schemaFromUrl("jdbc:as400://h;libraries=*LIBL,LIB")).isNull();
    assertThat(DatasourceDetector.schemaFromUrl("jdbc:as400://h;libraries=LIB1 LIB2")).isEqualTo("LIB1");
    assertThat(DatasourceDetector.schemaFromUrl("jdbc:postgresql://h/x")).isNull();
    assertThat(DatasourceDetector.schemaFromUrl(null)).isNull();
  }

  @Test
  void prefixeJpaVoisinEtIdentifiant() {
    assertThat(DatasourceDetector.jpaPrefix("spring.datasource")).isEqualTo("spring.jpa");
    assertThat(DatasourceDetector.jpaPrefix("spring.x.datasource")).isEqualTo("spring.x.jpa");
    assertThat(DatasourceDetector.jpaPrefix("x")).isEqualTo("x.jpa");
    assertThat(DatasourceDetector.idFor("spring.datasource")).isEqualTo("default");
    assertThat(DatasourceDetector.idFor("app.gpp.datasource")).isEqualTo("gpp");
    assertThat(DatasourceDetector.idFor("db.jdbc")).isEqualTo("db.jdbc");
  }
}
