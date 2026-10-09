package fr.cafat.meta.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.extract.sql.SqlAnalyzer.Analysis;
import fr.cafat.meta.model.SqlTable;
import org.junit.jupiter.api.Test;

class SqlAnalyzerTest {

  private static SqlTable read(String schema, String name) {
    return new SqlTable(schema, name, SqlAnalyzer.READ);
  }

  private static SqlTable write(String schema, String name) {
    return new SqlTable(schema, name, SqlAnalyzer.WRITE);
  }

  @Test
  void selectAvecJointureEtSousRequeteEnLecture() {
    Analysis a = SqlAnalyzer.analyze("SELECT p.id FROM gpp.personne p JOIN gpp.adresse a ON a.pid = p.id "
        + "WHERE p.id IN (SELECT pid FROM contrat)");

    assertThat(a.parsed()).isTrue();
    assertThat(a.error()).isNull();
    assertThat(a.tables()).containsExactly(read(null, "contrat"), read("gpp", "adresse"), read("gpp", "personne"));
  }

  @Test
  void cteNonListeeCommeTable() {
    Analysis a = SqlAnalyzer.analyze("WITH actifs AS (SELECT id FROM personne WHERE actif = 1) "
        + "SELECT * FROM actifs JOIN adresse ON adresse.pid = actifs.id");

    assertThat(a.parsed()).isTrue();
    assertThat(a.tables()).contains(read(null, "personne"), read(null, "adresse"));
  }

  @Test
  void ordresDeModificationEnEcriture() {
    assertThat(SqlAnalyzer.analyze("INSERT INTO s.t (a) VALUES (?)").tables()).containsExactly(write("s", "t"));
    assertThat(SqlAnalyzer.analyze("UPDATE t SET a = ? WHERE b = ?").tables()).containsExactly(write(null, "t"));
    assertThat(SqlAnalyzer.analyze("DELETE FROM t WHERE a = ?").tables()).containsExactly(write(null, "t"));
    assertThat(SqlAnalyzer.analyze("TRUNCATE TABLE t").tables()).containsExactly(write(null, "t"));
    assertThat(SqlAnalyzer.analyze("MERGE INTO cible c USING source s ON (c.id = s.id) "
        + "WHEN MATCHED THEN UPDATE SET c.a = s.a").tables())
        .contains(write(null, "cible"), read(null, "source"));
    assertThat(SqlAnalyzer.analyze("UPSERT INTO t (a) VALUES (?)").tables()).containsExactly(write(null, "t"));
  }

  @Test
  void ordresDeStructureEnEcriture() {
    assertThat(SqlAnalyzer.analyze("CREATE TABLE s.t (a INT)").tables()).containsExactly(write("s", "t"));
    assertThat(SqlAnalyzer.analyze("ALTER TABLE t ADD COLUMN b INT").tables()).containsExactly(write(null, "t"));
    assertThat(SqlAnalyzer.analyze("DROP TABLE t").tables()).containsExactly(write(null, "t"));
    assertThat(SqlAnalyzer.analyze("DROP VIEW v_t").tables()).containsExactly(write(null, "v_t"));
  }

  @Test
  void tableEcriteNonListeeEnLecture() {
    Analysis a = SqlAnalyzer.analyze("INSERT INTO archive SELECT * FROM archive_tmp UNION SELECT * FROM archive");

    assertThat(a.tables()).containsExactly(write(null, "archive"), read(null, "archive_tmp"));
  }

  @Test
  void plusieursInstructionsEcritureLEmporte() {
    Analysis a = SqlAnalyzer.analyze("SELECT a FROM t; UPDATE t SET a = 1; SELECT b FROM u");

    assertThat(a.parsed()).isTrue();
    assertThat(a.tables()).containsExactly(write(null, "t"), read(null, "u"));
  }

  @Test
  void nommageSystemeDb2() {
    Analysis a = SqlAnalyzer.analyze("SELECT * FROM MGENGPP/PERSONNE WHERE NOM = 'A/B'");

    assertThat(a.parsed()).isTrue();
    assertThat(a.tables()).containsExactly(read("MGENGPP", "PERSONNE"));
    assertThat(SqlAnalyzer.systemNaming("SELECT 'X/Y' FROM A/B")).isEqualTo("SELECT 'X/Y' FROM A.B");
  }

  @Test
  void marqueursHibernateRetires() {
    Analysis a = SqlAnalyzer.analyze("SELECT {p.*} FROM {h-schema}personne p JOIN {h-catalog}adresse a ON 1 = 1");

    assertThat(a.parsed()).isTrue();
    assertThat(a.tables()).containsExactly(read(null, "adresse"), read(null, "personne"));
    assertThat(SqlAnalyzer.withoutHibernatePlaceholders("SELECT {p.*} FROM {h-schema}t p"))
        .isEqualTo("SELECT p.* FROM t p");
  }

  @Test
  void repliLexicalSiLeParseurRejette() {
    Analysis a = SqlAnalyzer.analyze("SELECT a FROM t1 x, s.t2 WHERE ### INSERT INTO t3 VALUES");

    assertThat(a.parsed()).isFalse();
    assertThat(a.error()).isNotBlank().doesNotContain("\n");
    assertThat(a.tables()).contains(read(null, "t1"), read("s", "t2"), write(null, "t3"));
  }

  @Test
  void repliLexicalDeleteGuillemetsEtFonctionTable() {
    assertThat(SqlAnalyzer.fallback("DELETE FROM \"Sch\".\"Ma Table\" WHERE ???"))
        .containsExactly(write("Sch", "Ma Table"));
    assertThat(SqlAnalyzer.fallback("SELECT * FROM TABLE(fn(?)) -- FROM commentee\n")).isEmpty();
  }

  @Test
  void parametresMyBatisRemplaces() {
    assertThat(SqlAnalyzer.withoutMyBatisParams("SELECT * FROM t WHERE a = #{id,jdbcType=INTEGER} ORDER BY ${col}"))
        .isEqualTo("SELECT * FROM t WHERE a = ? ORDER BY ?");
  }

  @Test
  void parametresJdbiRemplacesTranstypageGarde() {
    assertThat(SqlAnalyzer.withoutJdbiParams("SELECT * FROM <table> WHERE id = :id AND u = :u.numero "
        + "AND d = :d::date AND x IN (<ids>)"))
        .isEqualTo("SELECT * FROM ? WHERE id = ? AND u = ? AND d = ?::date AND x IN (?)");
  }

  @Test
  void detectionEtNormalisation() {
    assertThat(SqlAnalyzer.looksLikeSql("  select a from t")).isTrue();
    assertThat(SqlAnalyzer.looksLikeSql("update t set a = 1")).isTrue();
    assertThat(SqlAnalyzer.looksLikeSql("Selectionnez une valeur")).isFalse();
    assertThat(SqlAnalyzer.looksLikeSql("SELECT 1")).isFalse();
    assertThat(SqlAnalyzer.looksLikeSql(null)).isFalse();
    assertThat(SqlAnalyzer.normalize("  SELECT\n\t a  FROM t WHERE b = '  x  '  "))
        .isEqualTo("SELECT a FROM t WHERE b = '  x  '");
  }
}
