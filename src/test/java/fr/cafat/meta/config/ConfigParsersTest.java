package fr.cafat.meta.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConfigParsersTest {

  private static Map<String, String> values(List<ConfigEntry> doc) {
    return doc.stream().collect(Collectors.toMap(ConfigEntry::key, ConfigEntry::value, (a, b) -> b));
  }

  private static ConfigEntry entry(List<ConfigEntry> doc, String key) {
    return doc.stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
  }

  // ---------------------------------------------------------------- properties

  @Test
  void proprietesSeparateursEtCommentaires() {
    List<List<ConfigEntry>> docs = PropertiesParser.parse("""
        # commentaire
        ! autre commentaire
        a=1
        b : 2
        c 3
        d=
        e
          f = indenté
        """, "app.properties");

    assertThat(docs).hasSize(1);
    assertThat(values(docs.get(0))).containsExactlyInAnyOrderEntriesOf(Map.of(
        "a", "1", "b", "2", "c", "3", "d", "", "e", "", "f", "indenté"));
    assertThat(entry(docs.get(0), "a").line()).isEqualTo(3);
    assertThat(entry(docs.get(0), "a").file()).isEqualTo("app.properties");
  }

  @Test
  void proprietesContinuationEtLigneDeDepart() {
    List<ConfigEntry> doc = PropertiesParser.parse("""
        x=1
        url=http://h\\
            /api\\
            /v1
        y=2
        """, "f").get(0);

    assertThat(entry(doc, "url").value()).isEqualTo("http://h/api/v1");
    assertThat(entry(doc, "url").line()).isEqualTo(2);
    assertThat(entry(doc, "y").line()).isEqualTo(5);
  }

  @Test
  void proprietesEchappements() {
    List<ConfigEntry> doc = PropertiesParser.parse("""
        cle\\ avec\\:espace=valeur
        tab=a\\tb
        unicode=\\u00e9t\\u00E9
        antislash=c\\\\\\\\d
        fin=x\\\\
        """, "f").get(0);

    assertThat(values(doc)).containsEntry("cle avec:espace", "valeur")
        .containsEntry("tab", "a\tb")
        .containsEntry("unicode", "été")
        .containsEntry("antislash", "c\\\\d")
        .containsEntry("fin", "x\\");
  }

  @Test
  void proprietesMultiDocuments() {
    List<List<ConfigEntry>> docs = PropertiesParser.parse("a=1\n#---\nspring.config.activate.on-profile=prod\na=2\n",
        "f");

    assertThat(docs).hasSize(2);
    assertThat(values(docs.get(0))).containsExactly(Map.entry("a", "1"));
    assertThat(values(docs.get(1))).containsEntry("a", "2");
    assertThat(entry(docs.get(1), "a").line()).isEqualTo(4);
  }

  // ---------------------------------------------------------------- yaml

  @Test
  void yamlImbricationEtLignes() {
    List<ConfigEntry> doc = YamlFlattener.parse("""
        spring:
          datasource:
            url: jdbc:postgresql://h/db
            hikari:
              maximum-pool-size: 5
        server.port: 8080
        """, "application.yml").get(0);

    assertThat(values(doc)).containsExactlyInAnyOrderEntriesOf(Map.of(
        "spring.datasource.url", "jdbc:postgresql://h/db",
        "spring.datasource.hikari.maximum-pool-size", "5",
        "server.port", "8080"));
    assertThat(entry(doc, "spring.datasource.url").line()).isEqualTo(3);
    assertThat(entry(doc, "server.port").line()).isEqualTo(6);
  }

  @Test
  void yamlListes() {
    List<ConfigEntry> doc = YamlFlattener.parse("""
        hosts:
          - a
          - b
        routes:
          - path: /x
            url: http://x
          - path: /y
        """, "f").get(0);

    assertThat(values(doc)).containsEntry("hosts[0]", "a")
        .containsEntry("hosts[1]", "b")
        .containsEntry("hosts", "a,b")
        .containsEntry("routes[0].path", "/x")
        .containsEntry("routes[0].url", "http://x")
        .containsEntry("routes[1].path", "/y")
        .doesNotContainKey("routes");
    assertThat(entry(doc, "hosts[1]").line()).isEqualTo(3);
  }

  @Test
  void yamlMultiDocumentsEtCleEntreCrochets() {
    List<List<ConfigEntry>> docs = YamlFlattener.parse("""
        a: 1
        ---
        map:
          "[a.b]": v
        """, "f");

    assertThat(docs).hasSize(2);
    assertThat(values(docs.get(0))).containsExactly(Map.entry("a", "1"));
    assertThat(values(docs.get(1))).containsExactly(Map.entry("map.a.b", "v"));
  }

  @Test
  void yamlScalairesGardesEnTexte() {
    List<ConfigEntry> doc = YamlFlattener.parse("""
        actif: true
        vide:
        nombre: 007
        cite: 'a: b'
        """, "f").get(0);

    assertThat(values(doc)).containsEntry("actif", "true")
        .containsEntry("vide", "")
        .containsEntry("nombre", "007")
        .containsEntry("cite", "a: b");
  }

  @Test
  void yamlCleDupliqueeLaDerniereGagneAuChargement() {
    List<ConfigEntry> doc = YamlFlattener.parse("a: 1\na: 2\n", "f").get(0);

    Config c = new Config();
    doc.forEach(c::put);

    assertThat(c.get("a")).isEqualTo("2");
  }
}
