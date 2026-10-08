package fr.cafat.meta.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretsTest {

  @Test
  void clesSensibles() {
    assertThat(Secrets.isSensitiveKey("spring.datasource.password")).isTrue();
    assertThat(Secrets.isSensitiveKey("spring.datasource.username")).isTrue();
    assertThat(Secrets.isSensitiveKey("app.db400.user")).isTrue();
    assertThat(Secrets.isSensitiveKey("api.token")).isTrue();
    assertThat(Secrets.isSensitiveKey("partenaire.client-secret")).isTrue();
    assertThat(Secrets.isSensitiveKey("app.api_key")).isTrue();
    assertThat(Secrets.isSensitiveKey("ldap.credentials[0]")).isTrue();
    assertThat(Secrets.isSensitiveKey("spring.datasource.url")).isFalse();
    assertThat(Secrets.isSensitiveKey("spring.application.name")).isFalse();
    assertThat(Secrets.isSensitiveKey("app.user-agent")).isFalse();
    assertThat(Secrets.isSensitiveKey(null)).isFalse();
  }

  @Test
  void urlsNettoyees() {
    assertThat(Secrets.sanitizeUrl("jdbc:postgresql://moi:mdp@pg:5432/gpp?currentSchema=sgengpp&password=x"))
        .isEqualTo("jdbc:postgresql://pg:5432/gpp?currentSchema=sgengpp&password=***");
    assertThat(Secrets.sanitizeUrl("jdbc:as400://as400;naming=system;user=MOI;password=MDP;libraries=MGENGPP"))
        .isEqualTo("jdbc:as400://as400;naming=system;user=***;password=***;libraries=MGENGPP");
    assertThat(Secrets.sanitizeUrl("jdbc:oracle:thin:moi/mdp@//ora:1521/SVC"))
        .isEqualTo("jdbc:oracle:thin:@//ora:1521/SVC");
    assertThat(Secrets.sanitizeUrl("jdbc:oracle:thin:@ora:1521:SID")).isEqualTo("jdbc:oracle:thin:@ora:1521:SID");
    assertThat(Secrets.sanitizeUrl("https://jeton@github.com/cafat/s-gen-gpp.git"))
        .isEqualTo("https://github.com/cafat/s-gen-gpp.git");
    assertThat(Secrets.sanitizeUrl("http://s-partenaire/api?api_key=abc&x=1")).isEqualTo("http://s-partenaire/api?api_key=***&x=1");
    assertThat(Secrets.sanitizeUrl("http://s-gen-gpp/gpp/api")).isEqualTo("http://s-gen-gpp/gpp/api");
    assertThat(Secrets.sanitizeUrl(null)).isNull();
  }
}
