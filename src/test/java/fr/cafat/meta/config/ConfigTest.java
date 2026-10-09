package fr.cafat.meta.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigTest {

  private static Config config(String... keyValues) {
    Config c = new Config();
    for (int i = 0; i < keyValues.length; i += 2) {
      c.put(new ConfigEntry(keyValues[i], keyValues[i + 1], "application.yml", i / 2 + 1));
    }
    return c;
  }

  @Test
  void espaceReserveResoluRecursivement() {
    Config c = config("api.host", "s-cible", "api.port", "8080",
        "api.base", "http://${api.host}:${api.port}", "api.url", "${api.base}/v1");

    assertThat(c.get("api.url")).isEqualTo("http://s-cible:8080/v1");
    assertThat(c.raw("api.url")).isEqualTo("${api.base}/v1");
  }

  @Test
  void valeurParDefautSiCleAbsente() {
    Config c = config("a", "${absent:http://defaut:80}", "b", "${absent:${repli}}", "repli", "r",
        "c", "${absent:}");

    assertThat(c.get("a")).isEqualTo("http://defaut:80");
    assertThat(c.get("b")).isEqualTo("r");
    assertThat(c.get("c")).isEmpty();
  }

  @Test
  void cleEnEspaceReserveImbrique() {
    Config c = config("env", "prod", "url.prod", "http://prod", "url", "${url.${env}}");

    assertThat(c.get("url")).isEqualTo("http://prod");
  }

  @Test
  void clesManquantesGardeesEtListees() {
    Config c = config("url", "http://${api.host}/${api.path}", "api.path", "v1");

    Config.Resolution r = c.resolve(c.raw("url"));

    assertThat(r.value()).isEqualTo("http://${api.host}/v1");
    assertThat(r.missing()).containsExactly("api.host");
    assertThat(r.complete()).isFalse();
    assertThat(c.get("url")).isNull();
  }

  @Test
  void expressionSpelInconnue() {
    Config c = config("x", "#{systemProperties['h']}/api");

    Config.Resolution r = c.resolve(c.raw("x"));

    assertThat(r.value()).isEqualTo("#{systemProperties['h']}/api");
    assertThat(r.missing()).containsExactly("#{systemProperties['h']}");
    assertThat(c.get("x")).isNull();
  }

  @Test
  void accoladeNonFermeeGardeeTelleQuelle() {
    Config c = config();

    Config.Resolution r = c.resolve("http://${api.host");

    assertThat(r.value()).isEqualTo("http://${api.host");
    assertThat(r.missing()).isEmpty();
  }

  @Test
  void rechercheRelachee() {
    Config c = config("api.base-url", "http://a");

    assertThat(c.get("api.baseUrl")).isEqualTo("http://a");
    assertThat(c.get("api.base_url")).isEqualTo("http://a");
    assertThat(c.get("API.BASEURL")).isEqualTo("http://a");
    assertThat(c.entry("api.baseUrl").key()).isEqualTo("api.base-url");
    assertThat(c.resolve("${api.baseUrl}/x").value()).isEqualTo("http://a/x");
  }

  @Test
  void derniereEntreeAjouteeGagne() {
    Config c = config("a", "1");
    c.put(new ConfigEntry("a", "2", "application-prod.yml", 3));

    assertThat(c.get("a")).isEqualTo("2");
    assertThat(c.entry("a").file()).isEqualTo("application-prod.yml");
    assertThat(c.entries()).hasSize(1);
  }

  @Test
  void clesSensiblesMasquees() {
    Config c = config("spring.datasource.password", "Secret-42", "db.user", "admin",
        "url", "jdbc:x://h?pw=${spring.datasource.password}", "jeton", "${api.token:defaut-secret}");

    assertThat(c.get("spring.datasource.password")).isEqualTo(Secrets.MASK);
    assertThat(c.get("db.user")).isEqualTo(Secrets.MASK);
    assertThat(c.get("url")).isEqualTo("jdbc:x://h?pw=***");
    assertThat(c.get("jeton")).isEqualTo(Secrets.MASK);
    assertThat(c.raw("spring.datasource.password")).isEqualTo("Secret-42");
  }

  @Test
  void premiereValeurParmiDesAlternatives() {
    Config c = config("vide", "", "incomplete", "${absent}", "b", "valeur-b");

    assertThat(c.first("inconnue", "vide", "incomplete", "b")).isEqualTo("valeur-b");
    assertThat(c.first("inconnue")).isNull();
    assertThat(c.firstEntry("inconnue", "incomplete", "b").key()).isEqualTo("incomplete");
    assertThat(c.firstEntry("inconnue")).isNull();
  }

  @Test
  void cycleArreteParLaProfondeurMax() {
    Config c = config("a", "${b}", "b", "${a}");

    Config.Resolution r = c.resolve("${a}");

    assertThat(r.complete()).isFalse();
    assertThat(c.get("a")).isNull();
  }

  @Test
  void configurationVide() {
    assertThat(Config.empty().isEmpty()).isTrue();
    assertThat(Config.empty().get("a")).isNull();
    assertThat(Config.empty().resolve(null).value()).isNull();
    assertThat(config("a", "1").isEmpty()).isFalse();
  }

  @Test
  void normalisationRelachee() {
    assertThat(Config.normalize("Spring.Data-Source.JDBC_url")).isEqualTo("spring.datasource.jdbcurl");
  }
}
