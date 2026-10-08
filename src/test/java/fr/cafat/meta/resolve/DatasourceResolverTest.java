package fr.cafat.meta.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.ConfigEntry;
import fr.cafat.meta.config.DatasourceDetector;
import fr.cafat.meta.config.DatasourceDetector.Detected;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.PersistenceExtractor;
import fr.cafat.meta.extract.entities.EntityDraft;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.extract.sql.SqlExtractor;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.Relation;
import fr.cafat.meta.model.SqlAccess;
import fr.cafat.meta.model.TableRef;
import fr.cafat.meta.scan.WebModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasourceResolverTest {

  record Run(ExtractionContext ctx, PersistenceExtractor.Result persistence, DatasourceResolver.Result result) {
    List<String> entities() {
      return persistence.drafts().values().stream()
          .filter(d -> d.table != null)
          .map(d -> d.className + " " + d.datasource + " " + d.schema + "." + d.table + " " + d.isView)
          .sorted().toList();
    }

    List<String> sql() {
      return result.sqlAccesses().stream()
          .map(a -> a.caller() + " " + a.origin() + " " + a.datasource() + " " + a.tables().stream()
              .map(t -> t.schema() + "." + t.name()).collect(Collectors.joining(",")))
          .sorted().toList();
    }

    List<String> messages(String code) {
      return ctx.diagnostics().all().stream().filter(d -> d.code().equals(code)).map(Diagnostic::message)
          .sorted().toList();
    }
  }

  static Run run(ExtractionContext ctx) {
    PersistenceExtractor.Result persistence = PersistenceExtractor.run(ctx);
    List<SqlExtractor.SqlDraft> sql = new SqlExtractor(ctx).extract();
    List<Detected> detected = DatasourceDetector.detect(ctx.config(), ctx.persistenceUnits());
    DatasourceResolver.Result result = new DatasourceResolver(ctx, detected, persistence.drafts())
        .resolve(sql, persistence.relations());
    return new Run(ctx, persistence, result);
  }

  @Test
  void fixtureSpring() {
    Run r = run(TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-spring"), "s-gen-fixture",
        NamingStrategy.springBoot(), List.of()));
    String pg = "fr.cafat.gpp.pg.domain.";
    String db2 = "fr.cafat.gpp.db2.domain.";
    assertThat(r.entities()).containsExactly(
        db2 + "GeoCommune db400 cafgeo.geo_commune null",
        db2 + "PersonneDb2 db400 mgengpp.gpp_personne_physique true",
        db2 + "Union db400 mgengpp.v_union true",
        pg + "ArchiveObsolete pg sgengpp.archive_obsolete null",
        pg + "Courriel pg sgengpp.gpp_courriel null",
        pg + "Deces pg sgengpp.gpp_evenement null",
        pg + "Evenement pg sgengpp.gpp_evenement null",
        pg + "Groupe pg sgengpp.gpp_groupe null",
        pg + "MoyenContact pg sgengpp.gpp_moyen_contact null",
        pg + "Naissance pg sgengpp.gpp_evenement null",
        pg + "PGUnion pg sgengpp.gpp_union null",
        pg + "PersonnePhysique pg sgengpp.gpp_personne_physique null",
        pg + "Telephone pg sgengpp.gpp_telephone null");
    String dao = "fr.cafat.gpp.pg.dao.ContactDao#";
    String mapper = "fr.cafat.gpp.db2.mapper.GeoMapper#";
    String repo = "fr.cafat.gpp.pg.repository.PersonneRepository#";
    assertThat(r.sql()).containsExactly(
        "fr.cafat.gpp.db2.dao.AdresseDb2Dao#adresses jdbc_template db400 MGENGPP.VW_ADRESSE",
        mapper + "findCommune mybatis_xml db400 CAFGEO.GEO_COMMUNE",
        mapper + "libellePays mybatis_annotation db400 CAFGEO.GEO_PAYS",
        mapper + "majLibelle mybatis_xml db400 CAFGEO.GEO_COMMUNE",
        "fr.cafat.gpp.db2.repository.UnionRepository#rechercher native_query db400 MGENGPP.V_UNION",
        dao + "ajouterCourriel jdbc_template pg sgengpp.gpp_courriel",
        dao + "evenements jdbc_template pg sgengpp.gpp_evenement",
        dao + "groupes named_parameter_jdbc_template pg sgengpp.gpp_groupe",
        dao + "purgerAlias jdbc_template pg sgengpp.gpp_personne_alias",
        dao + "telephones jdbc_template pg sgengpp.gpp_telephone",
        dao + "viderTemporaire string_literal pg sgengpp.gpp_import_tmp",
        repo + "findByMoyenContact native_query pg sgengpp.gpp_moyen_contact,sgengpp.gpp_personne_physique",
        repo + "renommer native_query pg sgengpp.gpp_personne_physique",
        "null named_native_query pg sgengpp.gpp_union");
    assertThat(r.messages("ENTITY_UNREFERENCED"))
        .containsExactly("Entité jamais référencée dans le code : " + pg + "ArchiveObsolete");
    assertThat(r.messages("DATASOURCE_AMBIGUOUS")).isEmpty();
    EntityDraft audit = r.persistence().drafts().values().stream()
        .filter(d -> d.className.equals(pg + "AbstractAuditEntity")).findFirst().orElseThrow();
    assertThat(audit.datasource).isEqualTo("pg");
  }

  @Test
  void fixtureJboss() {
    Run r = run(TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-jboss-multi"), "legacy",
        NamingStrategy.jpa(), List.of()));
    assertThat(r.entities()).containsExactly(
        "fr.cafat.legacy.db2.VuePersonne as400PU MGENGPP.VW_PERSONNE true",
        "fr.cafat.legacy.domain.Assure gppPU legacy.ASSURE null",
        "fr.cafat.legacy.domain.Contrat gppPU legacy.Contrat null");
    String dao = "fr.cafat.legacy.dao.ContratDao#";
    assertThat(r.sql()).containsExactly(
        dao + "adresses native_query as400PU MGENGPP.VW_ADRESSE",
        dao + "cloturer native_query gppPU legacy.contrat",
        dao + "personnes native_query as400PU MGENGPP.VW_PERSONNE");
    assertThat(r.messages("DATASOURCE_AMBIGUOUS")).isEmpty();
  }

  @Test
  void beansSpring(@TempDir Path dir) throws Exception {
    ExtractionContext base = TestContexts.ofSources(dir, NamingStrategy.springBoot(),
        """
        package fr.x.config;
        import javax.sql.DataSource;
        import org.apache.ibatis.session.SqlSessionFactory;
        import org.mybatis.spring.annotation.MapperScan;
        import org.springframework.beans.factory.annotation.Qualifier;
        import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
        import org.springframework.boot.context.properties.ConfigurationProperties;
        import org.springframework.context.annotation.*;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        import org.springframework.jdbc.core.JdbcTemplate;
        import org.springframework.jdbc.datasource.lookup.JndiDataSourceLookup;
        import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
        @Configuration
        @EnableJpaRepositories(basePackageClasses = fr.x.one.repo.Marker.class)
        @MapperScan("fr.x.two.mapper")
        public class Conf {
          @Bean
          @ConfigurationProperties("app.ds.one")
          public DataSourceProperties oneProps() { return new DataSourceProperties(); }
          @Bean
          @Primary
          public DataSource one() { return oneProps().initializeDataSourceBuilder().build(); }
          @Bean
          @ConfigurationProperties(prefix = "app.ds.two.hikari")
          public DataSource two() { return null; }
          @Bean
          public DataSource three() { return new JndiDataSourceLookup().getDataSource("java:/jdbc/Three"); }
          @Bean
          public DataSource routing(@Qualifier("one") DataSource a, @Qualifier("two") DataSource b) { return a; }
          @Bean
          public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean f = new LocalContainerEntityManagerFactoryBean();
            f.setDataSource(dataSource);
            f.setPackagesToScan("fr.x.one.domain");
            return f;
          }
          @Bean
          public SqlSessionFactory sessions(@Qualifier("two") DataSource ds) { return null; }
          @Bean
          public JdbcTemplate jdbcTwo(@Qualifier("two") DataSource ds) { return new JdbcTemplate(ds); }
          @Bean
          public JdbcTemplate jdbcThree() { return new JdbcTemplate(three()); }
        }
        """,
        "package fr.x.one.repo;\npublic interface Marker { }\n",
        """
        package fr.x.one.repo;
        import org.springframework.data.jpa.repository.*;
        public interface ClientRepository extends JpaRepository<fr.x.one.domain.Client, Long> {
          @Query(value = "SELECT * FROM client", nativeQuery = true)
          java.util.List<Object[]> tous();
        }
        """,
        """
        package fr.x.two.mapper;
        import org.apache.ibatis.annotations.*;
        @Mapper
        public interface TrucMapper {
          @Select("SELECT * FROM TRUC")
          java.util.List<Object> tous();
        }
        """,
        """
        package fr.x.dao;
        import org.springframework.beans.factory.annotation.*;
        import org.springframework.jdbc.core.JdbcTemplate;
        public class Dao {
          @Autowired private JdbcTemplate jdbcThree;
          @Autowired @Qualifier("jdbcTwo") private JdbcTemplate jt;
          @Autowired private JdbcTemplate autre;
          private javax.persistence.EntityManager em;
          public void tout() {
            jdbcThree.queryForList("SELECT * FROM t3");
            jt.update("DELETE FROM x WHERE a = 1");
            autre.execute("TRUNCATE TABLE y");
            em.createQuery("SELECT o FROM Orphan o").getResultList();
          }
        }
        """,
        """
        package fr.x.one.domain;
        import javax.persistence.*;
        @Entity
        @Table(name = "client")
        public class Client {
          @Id Long id;
          @ManyToMany
          @JoinTable(name = "client_tag")
          java.util.Set<Tag> tags;
        }
        """,
        """
        package fr.x.one.domain;
        import javax.persistence.*;
        @Entity
        public class Sub extends Client { }
        """,
        """
        package fr.x.one.domain;
        import javax.persistence.*;
        @Entity
        @Table(name = "tag")
        public class Tag { @Id Long id; }
        """,
        """
        package fr.x.one.domain;
        import javax.persistence.*;
        @Entity
        @Table(name = "vw_client")
        public class ClientVue { @Id Long id; }
        """,
        """
        package fr.x.one.domain;
        import javax.persistence.*;
        /** Copie de la vue AS400. */
        @Entity
        @Table(name = "archive")
        public class Archive { @Id Long id; }
        """,
        """
        package fr.x.other;
        import javax.persistence.*;
        @Entity
        public class Orphan { @Id Long id; }
        """);
    Config config = Config.empty();
    config.put(new ConfigEntry("app.ds.one.url", "jdbc:postgresql://h/db?currentSchema=s1,public", "a.yml", 1));
    config.put(new ConfigEntry("app.ds.two.jdbc-url", "jdbc:as400://h;libraries=LIB2", "a.yml", 2));
    config.put(new ConfigEntry("spring.datasource.three.jndi-name", "java:/jdbc/Three", "a.yml", 3));
    List<Path> java;
    try (Stream<Path> s = Files.walk(dir)) {
      java = s.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    }
    Run r = run(TestContexts.build(base.root(), java, "app", NamingStrategy.springBoot(), new Diagnostics(),
        List.of(WebModule.ROOT), config, List.of()));
    assertThat(r.entities()).containsExactly(
        "fr.x.one.domain.Archive one s1.archive true",
        "fr.x.one.domain.Client one s1.client null",
        "fr.x.one.domain.ClientVue one s1.vw_client true",
        "fr.x.one.domain.Sub one s1.client null",
        "fr.x.one.domain.Tag one s1.tag null",
        "fr.x.other.Orphan null null.orphan null");
    assertThat(r.sql()).containsExactly(
        "fr.x.dao.Dao#tout jdbc_template null null.y",
        "fr.x.dao.Dao#tout jdbc_template three null.t3",
        "fr.x.dao.Dao#tout jdbc_template two LIB2.x",
        "fr.x.one.repo.ClientRepository#tous native_query one s1.client",
        "fr.x.two.mapper.TrucMapper#tous mybatis_annotation two LIB2.TRUC");
    Relation tags = r.result().relations().stream().filter(x -> "tags".equals(x.field())).findFirst()
        .orElseThrow();
    assertThat(tags.joinTable()).isEqualTo(new TableRef("s1", "client_tag"));
    assertThat(r.messages("DATASOURCE_AMBIGUOUS")).hasSize(2)
        .anyMatch(m -> m.contains("fr.x.other.Orphan"))
        .anyMatch(m -> m.contains("fr.x.dao.Dao#tout"));
    assertThat(r.messages("ENTITY_UNREFERENCED")).containsExactly(
        "Entité jamais référencée dans le code : fr.x.one.domain.Archive",
        "Entité jamais référencée dans le code : fr.x.one.domain.ClientVue");
    SqlAccess client = r.result().sqlAccesses().stream().filter(a -> a.origin().equals("native_query"))
        .findFirst().orElseThrow();
    assertThat(client.id()).startsWith("app:sql:");
  }
}
