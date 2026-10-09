package fr.cafat.meta.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.extract.sql.MyBatisXml.Statement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MyBatisXmlTest {

  private static List<Statement> read(Path dir, String xml) throws IOException, XMLStreamException {
    Path f = dir.resolve("Mapper.xml");
    Files.writeString(f, xml);
    return MyBatisXml.read(f);
  }

  @Test
  void requetesDuMapperDansLOrdre(@TempDir Path dir) throws Exception {
    List<Statement> st = read(dir, """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="nc.cafat.PersonneMapper">
          <select id="find">SELECT * FROM personne WHERE id = #{id}</select>
          <insert id="add">INSERT INTO personne (id) VALUES (#{id})</insert>
          <update id="maj">UPDATE personne SET nom = #{nom}</update>
          <delete id="del">DELETE FROM personne</delete>
          <resultMap id="r" type="X"/>
        </mapper>
        """);

    assertThat(st).extracting(Statement::id).containsExactly("find", "add", "maj", "del");
    assertThat(st).extracting(Statement::kind).containsExactly("select", "insert", "update", "delete");
    assertThat(st).allMatch(s -> s.namespace().equals("nc.cafat.PersonneMapper") && !s.dynamic());
    assertThat(st.get(0).sql()).isEqualTo("SELECT * FROM personne WHERE id = #{id}");
    assertThat(st.get(0).line()).isEqualTo(4);
    assertThat(st.get(3).line()).isEqualTo(7);
  }

  @Test
  void fragmentsIncludeRemplacesYComprisImbriques(@TempDir Path dir) throws Exception {
    List<Statement> st = read(dir, """
        <mapper namespace="ns">
          <sql id="cols">id, nom</sql>
          <sql id="base">SELECT <include refid="cols"/> FROM personne</sql>
          <select id="a"><include refid="ns.base"/> WHERE id = 1</select>
        </mapper>
        """);

    assertThat(st).singleElement().satisfies(s -> {
      assertThat(s.sql()).isEqualTo("SELECT id, nom FROM personne WHERE id = 1");
      assertThat(s.dynamic()).isFalse();
    });
  }

  @Test
  void fragmentIntrouvableOuParametreDollarRendDynamique(@TempDir Path dir) throws Exception {
    List<Statement> st = read(dir, """
        <mapper namespace="ns">
          <select id="a">SELECT * FROM <include refid="absent"/></select>
          <select id="b">SELECT * FROM ${table}</select>
        </mapper>
        """);

    assertThat(st).extracting(Statement::dynamic).containsExactly(true, true);
  }

  @Test
  void includeCycliqueBorne(@TempDir Path dir) throws Exception {
    List<Statement> st = read(dir, """
        <mapper namespace="ns">
          <sql id="boucle">x <include refid="boucle"/></sql>
          <select id="a">SELECT * FROM t WHERE <include refid="boucle"/></select>
        </mapper>
        """);

    assertThat(st).singleElement().satisfies(s -> assertThat(s.dynamic()).isTrue());
  }

  @Test
  void balisesDynamiquesAplatiesToutesBranchesGardees(@TempDir Path dir) throws Exception {
    List<Statement> st = read(dir, """
        <mapper namespace="ns">
          <select id="s">
            SELECT * FROM personne
            <where>
              <if test="nom != null">AND nom = #{nom}</if>
              <choose>
                <when test="a">OR a = 1</when>
                <otherwise>OR b = 2</otherwise>
              </choose>
            </where>
          </select>
          <update id="u">
            UPDATE personne <set><if test="nom != null">nom = #{nom},</if></set>
          </update>
          <select id="f">
            SELECT * FROM t WHERE id IN
            <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
          </select>
          <insert id="t">
            INSERT INTO t <trim prefix="(" suffix=")" suffixOverrides=",">a, b,</trim> VALUES (1, 2)
            <selectKey keyProperty="id">SELECT seq.nextval FROM dual</selectKey>
          </insert>
        </mapper>
        """);

    assertThat(st).extracting(Statement::sql).containsExactly(
        "SELECT * FROM personne WHERE nom = #{nom} OR a = 1 OR b = 2",
        "UPDATE personne SET nom = #{nom}",
        "SELECT * FROM t WHERE id IN (#{i})",
        "INSERT INTO t ( a, b ) VALUES (1, 2)");
    assertThat(st).noneMatch(Statement::dynamic);
  }

  @Test
  void fichierQuiNEstPasUnMapper(@TempDir Path dir) throws Exception {
    assertThat(read(dir, "<beans><select id=\"a\">SELECT 1 FROM t</select></beans>")).isEmpty();
    assertThat(MyBatisXml.isMapper("<beans/>")).isFalse();
    assertThat(MyBatisXml.isMapper("<?xml version=\"1.0\"?>\n<mapper namespace=\"x\">")).isTrue();
  }

  @Test
  void scriptDAnnotation() {
    Statement s = MyBatisXml.script(
        "<script>SELECT * FROM t <where><if test='a'>AND a = #{a}</if></where></script>");

    assertThat(s.id()).isEqualTo("script");
    assertThat(s.kind()).isEqualTo("select");
    assertThat(s.sql()).isEqualTo("SELECT * FROM t WHERE a = #{a}");
    assertThat(MyBatisXml.script("<script>SELECT * FROM t WHERE a < 1</script>")).isNull();
  }
}
