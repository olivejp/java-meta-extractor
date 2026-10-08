package fr.cafat.meta.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.config.Config;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.extract.sql.SqlExtractor.DatasourceHint;
import fr.cafat.meta.extract.sql.SqlExtractor.SqlDraft;
import fr.cafat.meta.model.Diagnostic;
import fr.cafat.meta.model.SqlAccess;
import fr.cafat.meta.model.SqlTable;
import fr.cafat.meta.scan.WebModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlExtractorTest {

  /** Résumé lisible d'un accès : origine, appelant, parsed, tables (schema.nom:accès). */
  static String summary(SqlAccess a) {
    String tables = a.tables().stream()
        .map(t -> (t.schema() == null ? "" : t.schema() + ".") + t.name() + ":" + t.access())
        .collect(Collectors.joining(","));
    return a.origin() + " " + a.caller() + " " + (a.parsed() ? "parsed" : "unparsed") + " " + tables;
  }

  static List<String> summaries(List<SqlDraft> drafts) {
    return drafts.stream().map(d -> summary(d.access())).sorted().toList();
  }

  static SqlDraft draft(List<SqlDraft> drafts, String caller, String origin) {
    return drafts.stream()
        .filter(d -> String.valueOf(d.access().caller()).equals(caller) && d.access().origin().equals(origin))
        .findFirst()
        .orElseThrow(() -> new AssertionError(caller + " absent : " + summaries(drafts)));
  }

  static List<String> messages(ExtractionContext ctx, String code) {
    return ctx.diagnostics().all().stream().filter(d -> d.code().equals(code)).map(Diagnostic::message).toList();
  }

  @Test
  void accesDeLaFixtureSpring() {
    ExtractionContext ctx = TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-spring"), "s-gen-fixture",
        NamingStrategy.springBoot(), List.of());
    List<SqlDraft> drafts = new SqlExtractor(ctx).extract();
    String pg = "fr.cafat.gpp.pg.";
    String db2 = "fr.cafat.gpp.db2.";
    assertThat(summaries(drafts)).containsExactly(
        "jdbc_template " + db2 + "dao.AdresseDb2Dao#adresses parsed MGENGPP.VW_ADRESSE:read",
        "jdbc_template " + pg + "dao.ContactDao#ajouterCourriel parsed sgengpp.gpp_courriel:write",
        "jdbc_template " + pg + "dao.ContactDao#evenements unparsed sgengpp.gpp_evenement:read",
        "jdbc_template " + pg + "dao.ContactDao#purgerAlias parsed sgengpp.gpp_personne_alias:write",
        "jdbc_template " + pg + "dao.ContactDao#telephones parsed sgengpp.gpp_telephone:read",
        "mybatis_annotation " + db2 + "mapper.GeoMapper#libellePays parsed CAFGEO.GEO_PAYS:read",
        "mybatis_xml " + db2 + "mapper.GeoMapper#findCommune parsed CAFGEO.GEO_COMMUNE:read",
        "mybatis_xml " + db2 + "mapper.GeoMapper#majLibelle parsed CAFGEO.GEO_COMMUNE:write",
        "named_native_query null parsed sgengpp.gpp_union:read",
        "named_parameter_jdbc_template " + pg + "dao.ContactDao#groupes parsed sgengpp.gpp_groupe:read",
        "native_query " + db2 + "repository.UnionRepository#rechercher parsed MGENGPP.V_UNION:read",
        "native_query " + pg + "repository.PersonneRepository#findByMoyenContact parsed "
            + "sgengpp.gpp_moyen_contact:read,sgengpp.gpp_personne_physique:read",
        "native_query " + pg + "repository.PersonneRepository#renommer parsed sgengpp.gpp_personne_physique:write",
        "string_literal " + pg + "dao.ContactDao#viderTemporaire parsed sgengpp.gpp_import_tmp:write");

    SqlDraft adresse = draft(drafts, db2 + "dao.AdresseDb2Dao#adresses", "jdbc_template");
    assertThat(adresse.access().sql()).isEqualTo("SELECT * FROM MGENGPP/VW_ADRESSE WHERE NUMERO_INTERNE = ?");
    assertThat(adresse.hint()).isEqualTo(new DatasourceHint(db2 + "dao.AdresseDb2Dao", "db400JdbcTemplate", "db400",
        null));
    assertThat(adresse.access().id())
        .matches("s-gen-fixture:sql:[0-9a-f]{40}@fr\\.cafat\\.gpp\\.db2\\.dao\\.AdresseDb2Dao#adresses");
    assertThat(adresse.access().datasource()).isNull();

    SqlDraft evenements = draft(drafts, pg + "dao.ContactDao#evenements", "jdbc_template");
    assertThat(evenements.access().sql())
        .isEqualTo("SELECT * FROM sgengpp.gpp_evenement WHERE 1 = 1 AND type_evt = '{type}'");
    assertThat(messages(ctx, "SQL_UNPARSED")).singleElement().asString().contains("evenements").contains("type");

    SqlDraft renommer = draft(drafts, pg + "repository.PersonneRepository#renommer", "native_query");
    assertThat(renommer.access().sql())
        .isEqualTo("UPDATE sgengpp.gpp_personne_physique SET nom = :nom WHERE numero_interne = :id");
    assertThat(renommer.hint().owner()).isEqualTo(pg + "repository.PersonneRepository");

    SqlAccess union = drafts.stream().map(SqlDraft::access).filter(a -> a.origin().equals("named_native_query"))
        .findFirst().orElseThrow();
    assertThat(union.id()).endsWith("@" + pg + "domain.PGUnion");
    assertThat(union.source().file()).isEqualTo("src/main/java/fr/cafat/gpp/pg/domain/PGUnion.java");

    SqlDraft commune = draft(drafts, db2 + "mapper.GeoMapper#findCommune", "mybatis_xml");
    assertThat(commune.access().sql())
        .isEqualTo("SELECT CODE_COMMUNE, LIBELLE FROM CAFGEO.GEO_COMMUNE WHERE CODE_COMMUNE = #{code}");
    assertThat(commune.access().source().file()).isEqualTo("src/main/resources/mapper/GeoMapper.xml");
    assertThat(commune.access().source().className()).isEqualTo(db2 + "mapper.GeoMapper");
    assertThat(commune.access().source().line()).isPositive();
    assertThat(draft(drafts, db2 + "mapper.GeoMapper#majLibelle", "mybatis_xml").access().sql())
        .isEqualTo("UPDATE CAFGEO.GEO_COMMUNE SET LIBELLE = #{libelle} WHERE CODE_COMMUNE = #{code}");

    assertThat(draft(drafts, pg + "dao.ContactDao#groupes", "named_parameter_jdbc_template").hint().name())
        .isEqualTo("namedJdbc");
    assertThat(messages(ctx, "SQL_UNRESOLVED")).isEmpty();
  }

  @Test
  void accesDeLaFixtureJboss() {
    ExtractionContext ctx = TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-jboss-multi"), "legacy",
        NamingStrategy.jpa(), List.of());
    List<SqlDraft> drafts = new SqlExtractor(ctx).extract();
    String dao = "fr.cafat.legacy.dao.ContratDao#";
    assertThat(summaries(drafts)).containsExactly(
        "native_query " + dao + "adresses unparsed MGENGPP.VW_ADRESSE:read",
        "native_query " + dao + "cloturer parsed legacy.contrat:write",
        "native_query " + dao + "personnes parsed MGENGPP.VW_PERSONNE:read");
    assertThat(draft(drafts, dao + "personnes", "native_query").hint().unit()).isEqualTo("as400PU");
    assertThat(draft(drafts, dao + "cloturer", "native_query").hint().unit()).isEqualTo("gppPU");
    assertThat(draft(drafts, dao + "adresses", "native_query").access().sql())
        .isEqualTo("SELECT * FROM MGENGPP/VW_ADRESSE WHERE NUMERO_INTERNE = {numero}");
    assertThat(drafts).allSatisfy(d -> assertThat(d.access().source().file()).doesNotContain("src/test"));
    assertThat(messages(ctx, "SQL_UNPARSED")).singleElement().asString().contains("numero");
  }

  @Test
  void constructionsEtExclusions(@TempDir Path dir) throws IOException {
    ExtractionContext base = TestContexts.ofSources(dir, NamingStrategy.springBoot(),
        """
        package fr.x;
        import org.springframework.jdbc.core.JdbcTemplate;
        import org.springframework.jdbc.core.simple.JdbcClient;
        import javax.persistence.EntityManager;
        public class Dao {
          private static final String JPQL = "SELECT p FROM Personne p WHERE p.nom = :nom";
          private static final String PURGE = "DELETE FROM app.journal WHERE d < ?";
          private static final org.slf4j.Logger log = null;
          private JdbcTemplate jdbc;
          private JdbcClient client;
          private EntityManager em;
          public void builder(boolean filtre) {
            StringBuilder sb = new StringBuilder("SELECT a FROM app.t1");
            if (filtre) {
              sb.append(" WHERE b = 1");
            }
            jdbc.query(sb.toString(), rs -> { });
          }
          public void exclusions() {
            em.createQuery(JPQL).getResultList();
            log.info("SELECT termine FROM cache");
            if (em == null) {
              throw new IllegalStateException("UPDATE impossible : SET manquant");
            }
          }
          public void purge(Other other) {
            other.run(PURGE);
          }
          public void divers() {
            client.sql("SELECT x FROM app.t3 WITH UR").query();
            jdbc.queryForList("SELECT * FROM \\"Lib\\".\\"Ma Table\\" FETCH FIRST 10 ROWS ONLY");
            jdbc.execute("SELECT * FROM app.t5 WHERE a ~~~ b");
            jdbc.update("INSERT INTO app.cible SELECT * FROM app.source");
            jdbc.execute((java.sql.Connection c) -> null);
          }
          public void parametre(String sql) {
            jdbc.update(sql);
          }
          public String helper() {
            return jdbc.queryForObject(requete(), String.class);
          }
          private String requete() {
            return "SELECT nom FROM app.t7";
          }
        }
        """,
        """
        package fr.x;
        import org.apache.ibatis.annotations.*;
        @Mapper
        public interface XMapper {
          @Select("<script>SELECT * FROM app.t6 <where><if test='a != null'>AND a = #{a}</if></where></script>")
          java.util.List<Object> filtre(String a);
          @Delete("DELETE FROM ${table} WHERE id = #{id}")
          void supprimer(String table, long id);
          @Update({"UPDATE app.t8", "SET v = #{v}"})
          void maj(int v);
        }
        """,
        """
        package fr.x;
        import javax.persistence.*;
        @Entity
        @NamedNativeQueries({
          @NamedNativeQuery(name = "a", query = "SELECT * FROM app.e1"),
          @NamedNativeQuery(name = "b", query = "DELETE FROM app.e1 WHERE x = 1")
        })
        public class E { @Id Long id; }
        """);
    Path xml = dir.resolve("XMapper.xml");
    Files.writeString(xml, """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="fr.x.XMapper">
          <sql id="cols">a, b</sql>
          <select id="liste">
            SELECT <include refid="fr.x.XMapper.cols"/> FROM app.t9
            <where>
              <if test="a != null">AND a = #{a}</if>
              <if test="b != null">OR b IN
                <foreach collection="b" item="i" open="(" separator="," close=")">#{i}</foreach>
              </if>
            </where>
          </select>
          <update id="maj">
            UPDATE app.t9 <set><if test="a != null">a = #{a},</if></set> WHERE id = #{id}
          </update>
          <insert id="ajout">
            <selectKey keyProperty="id" resultType="long" order="BEFORE">SELECT nextval('app.seq')</selectKey>
            INSERT INTO app.t9 (id, a) VALUES (#{id}, #{a})
          </insert>
          <delete id="inconnu">DELETE FROM app.t9 <include refid="absent"/></delete>
        </mapper>
        """, StandardCharsets.UTF_8);
    ExtractionContext ctx = TestContexts.build(base.root(), javaFiles(dir), "app", NamingStrategy.springBoot(),
        base.diagnostics(), List.of(WebModule.ROOT), Config.empty(), List.of(xml));
    List<SqlDraft> drafts = new SqlExtractor(ctx).extract();
    assertThat(summaries(drafts)).containsExactly(
        "jdbc_template fr.x.Dao#builder unparsed app.t1:read",
        "jdbc_template fr.x.Dao#divers parsed Lib.Ma Table:read",
        "jdbc_template fr.x.Dao#divers parsed app.cible:write,app.source:read",
        "jdbc_template fr.x.Dao#divers parsed app.t3:read",
        "jdbc_template fr.x.Dao#divers unparsed app.t5:read",
        "jdbc_template fr.x.Dao#helper parsed app.t7:read",
        "mybatis_annotation fr.x.XMapper#filtre parsed app.t6:read",
        "mybatis_annotation fr.x.XMapper#maj parsed app.t8:write",
        "mybatis_annotation fr.x.XMapper#supprimer unparsed ",
        "mybatis_xml fr.x.XMapper#ajout parsed app.t9:write",
        "mybatis_xml fr.x.XMapper#inconnu unparsed app.t9:write",
        "mybatis_xml fr.x.XMapper#liste parsed app.t9:read",
        "mybatis_xml fr.x.XMapper#maj parsed app.t9:write",
        "named_native_query null parsed app.e1:read",
        "named_native_query null parsed app.e1:write",
        "string_literal null parsed app.journal:write");

    assertThat(draft(drafts, "fr.x.Dao#builder", "jdbc_template").access().sql())
        .isEqualTo("SELECT a FROM app.t1 WHERE b = 1");
    assertThat(draft(drafts, "fr.x.XMapper#filtre", "mybatis_annotation").access().sql())
        .isEqualTo("SELECT * FROM app.t6 WHERE a = #{a}");
    assertThat(draft(drafts, "fr.x.XMapper#maj", "mybatis_annotation").access().sql())
        .isEqualTo("UPDATE app.t8 SET v = #{v}");
    assertThat(draft(drafts, "fr.x.XMapper#liste", "mybatis_xml").access().sql())
        .isEqualTo("SELECT a, b FROM app.t9 WHERE a = #{a} OR b IN (#{i})");
    assertThat(draft(drafts, "fr.x.XMapper#maj", "mybatis_xml").access().sql())
        .isEqualTo("UPDATE app.t9 SET a = #{a} WHERE id = #{id}");
    assertThat(draft(drafts, "fr.x.XMapper#ajout", "mybatis_xml").access().sql())
        .isEqualTo("INSERT INTO app.t9 (id, a) VALUES (#{id}, #{a})");
    assertThat(messages(ctx, "SQL_UNRESOLVED")).singleElement().asString().contains("parametre");
    assertThat(messages(ctx, "SQL_UNPARSED")).hasSize(4)
        .anyMatch(m -> m.contains("JSqlParser") && m.contains("divers"));
  }

  @Test
  void analyseDesTables() {
    assertThat(SqlAnalyzer.analyze("WITH x AS (SELECT * FROM s.c) SELECT * FROM x JOIN s.d ON 1 = 1").tables())
        .containsExactly(new SqlTable("s", "c", "read"), new SqlTable("s", "d", "read"));
    assertThat(SqlAnalyzer.analyze("MERGE INTO s.t USING s.src ON (t.id = src.id) "
        + "WHEN MATCHED THEN UPDATE SET t.v = src.v").tables())
        .containsExactly(new SqlTable("s", "src", "read"), new SqlTable("s", "t", "write"));
    assertThat(SqlAnalyzer.analyze("TRUNCATE TABLE LIB/TAB").tables())
        .containsExactly(new SqlTable("LIB", "TAB", "write"));
    assertThat(SqlAnalyzer.analyze("UPDATE s.a SET v = (SELECT max(v) FROM s.b)").tables())
        .containsExactly(new SqlTable("s", "a", "write"), new SqlTable("s", "b", "read"));
    assertThat(SqlAnalyzer.analyze("DELETE FROM s.a WHERE id IN (SELECT id FROM s.a)").tables())
        .containsExactly(new SqlTable("s", "a", "write"));
    assertThat(SqlAnalyzer.analyze("SELECT 'MGENGPP/X' FROM s.a").tables())
        .containsExactly(new SqlTable("s", "a", "read"));

    assertThat(SqlAnalyzer.fallback("SELECT * FROM s.a x, s.b, ? WHERE ~~ JOIN \"Q\".\"T\" t ON"))
        .containsExactly(new SqlTable("s", "a", "read"), new SqlTable("s", "b", "read"),
            new SqlTable("Q", "T", "read"));
    assertThat(SqlAnalyzer.fallback("DELETE FROM L/T WHERE x = 'FROM faux'"))
        .containsExactly(new SqlTable("L", "T", "write"));
    assertThat(SqlAnalyzer.fallback("SELECT * FROM TABLE(f(1)) t")).isEmpty();

    assertThat(SqlAnalyzer.normalize("  SELECT\n\t a ,  'x   y'\n FROM t  ")).isEqualTo("SELECT a , 'x   y' FROM t");
    assertThat(SqlAnalyzer.systemNaming("SELECT * FROM L/T WHERE a = 'L/T' AND b = 4/2"))
        .isEqualTo("SELECT * FROM L.T WHERE a = 'L/T' AND b = 4/2");
    assertThat(SqlAnalyzer.looksLikeSql("select * from t")).isTrue();
    assertThat(SqlAnalyzer.looksLikeSql("Selection des dossiers")).isFalse();
    assertThat(SqlAnalyzer.looksLikeSql("UPDATE impossible")).isFalse();
  }

  private static List<Path> javaFiles(Path dir) {
    try (var s = Files.walk(dir)) {
      return s.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
