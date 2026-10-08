package fr.cafat.meta.extract.calls;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.model.Call;
import fr.cafat.meta.model.Diagnostic;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallExtractorTest {

  private static final String SVC = "fr.cafat.gpp.service.ReferentielService#";

  static Call call(List<Call> calls, String id) {
    return calls.stream().filter(c -> c.id().equals(id)).findFirst()
        .orElseThrow(() -> new AssertionError(id + " absent : " + calls.stream().map(Call::id).toList()));
  }

  static List<String> ids(List<Call> calls) {
    return calls.stream().map(Call::id).sorted().toList();
  }

  static List<String> messages(ExtractionContext ctx, String code) {
    return ctx.diagnostics().all().stream().filter(d -> d.code().equals(code)).map(Diagnostic::message).toList();
  }

  @Test
  void appelsDeLaFixtureSpring() {
    ExtractionContext ctx = TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-spring"), "s-gen-fixture",
        NamingStrategy.springBoot(), List.of());
    List<Call> calls = new CallExtractor(ctx).extract();
    String p = "s-gen-fixture:";
    assertThat(ids(calls)).containsExactly(
        p + "GET:?:/v2/status@" + SVC + "statutExterne",
        p + "GET:s-ref-adresse:/api/v1/adresses/{id}@fr.cafat.gpp.client.AdresseClient#getAdresse",
        p + "GET:s-ref-geo:/communes/{code}@" + SVC + "commune",
        p + "POST:s-gen-batch:/jobs/{nom}@" + SVC + "lancerJob",
        p + "POST:s-gen-notif:/notifications@fr.cafat.gpp.client.NotifClient#envoyer",
        p + "POST:s-ref-adresse:/api/v1/adresses@fr.cafat.gpp.client.AdresseClient#creerAdresse",
        p + "PUT:?:/partenaires@" + SVC + "majPartenaire");

    Call adresse = call(calls, p + "GET:s-ref-adresse:/api/v1/adresses/{id}@fr.cafat.gpp.client.AdresseClient#getAdresse");
    assertThat(adresse.client()).isEqualTo("feign");
    assertThat(adresse.method()).isEqualTo("GET");
    assertThat(adresse.rawUrl()).isEqualTo("${referentiel.url}/v1/adresses/{id}");
    assertThat(adresse.resolvedUrl()).isEqualTo("http://s-ref-adresse:8080/api/v1/adresses/{id}");
    assertThat(adresse.path()).isEqualTo("/api/v1/adresses/{id}");
    assertThat(adresse.targetApp()).isEqualTo("s-ref-adresse");
    assertThat(adresse.caller()).isEqualTo("fr.cafat.gpp.client.AdresseClient#getAdresse");
    assertThat(adresse.source().file()).isEqualTo("src/main/java/fr/cafat/gpp/client/AdresseClient.java");

    Call notif = call(calls, p + "POST:s-gen-notif:/notifications@fr.cafat.gpp.client.NotifClient#envoyer");
    assertThat(notif.rawUrl()).isEqualTo("/notifications");
    assertThat(notif.resolvedUrl()).isEqualTo("http://s-gen-notif/notifications");

    Call commune = call(calls, p + "GET:s-ref-geo:/communes/{code}@" + SVC + "commune");
    assertThat(commune.client()).isEqualTo("rest_template");
    assertThat(commune.rawUrl()).isEqualTo("${geo.url}/communes/{code}");
    assertThat(commune.resolvedUrl()).isEqualTo("http://s-ref-geo.referentiel.svc.cluster.local:8080/communes/{code}");

    Call partenaire = call(calls, p + "PUT:?:/partenaires@" + SVC + "majPartenaire");
    assertThat(partenaire.rawUrl()).isEqualTo("${PARTNER_URL}/partenaires");
    assertThat(partenaire.resolvedUrl()).isNull();
    assertThat(partenaire.targetApp()).isNull();

    Call job = call(calls, p + "POST:s-gen-batch:/jobs/{nom}@" + SVC + "lancerJob");
    assertThat(job.client()).isEqualTo("web_client");
    assertThat(job.rawUrl()).isEqualTo("http://s-gen-batch:8080/jobs/{nom}");
    assertThat(job.resolvedUrl()).isEqualTo("http://s-gen-batch:8080/jobs/{nom}");

    Call statut = call(calls, p + "GET:?:/v2/status@" + SVC + "statutExterne");
    assertThat(statut.client()).isEqualTo("rest_client");
    assertThat(statut.resolvedUrl()).isEqualTo("https://api.partenaire-externe.com/v2/status");
    assertThat(statut.targetApp()).isNull();

    assertThat(messages(ctx, "URL_UNRESOLVED")).singleElement().asString().contains("PARTNER_URL");
    assertThat(messages(ctx, "TARGET_APP_UNKNOWN")).singleElement().asString()
        .contains("api.partenaire-externe.com");
  }

  @Test
  void profilesAppliques() {
    ExtractionContext prod = TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-spring"), "s",
        NamingStrategy.springBoot(), List.of("prod"));
    List<Call> calls = new CallExtractor(prod).extract();
    assertThat(ids(calls)).contains(
        "s:GET:s-ref-adresse-prod:/api/v1/adresses/{id}@fr.cafat.gpp.client.AdresseClient#getAdresse");

    ExtractionContext dev = TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-spring"), "s",
        NamingStrategy.springBoot(), List.of("dev"));
    Call commune = call(new CallExtractor(dev).extract(), "s:GET:?:/communes/{code}@" + SVC + "commune");
    assertThat(commune.resolvedUrl()).isEqualTo("http://localhost:9090/communes/{code}");
    assertThat(messages(dev, "TARGET_APP_UNKNOWN")).anyMatch(m -> m.contains("localhost"));
  }

  @Test
  void clientJaxrs() {
    ExtractionContext ctx = TestContexts.ofRepoConfigured(TestContexts.fixture("fixture-jboss-multi"), "legacy",
        NamingStrategy.jpa(), List.of());
    List<Call> calls = new CallExtractor(ctx).extract();
    assertThat(ids(calls)).containsExactly("legacy:GET:?:/assures/{id}@fr.cafat.legacy.rest.ContratResource#assure");
    Call c = calls.get(0);
    assertThat(c.client()).isEqualTo("jaxrs_client");
    assertThat(c.rawUrl()).isEqualTo("${assure.url}/assures/{id}");
    assertThat(c.resolvedUrl()).isNull();
    assertThat(messages(ctx, "URL_UNRESOLVED")).singleElement().asString().contains("assure.url");
  }

  @Test
  void constructionsDeClientsEtCasLimites(@TempDir Path dir) {
    ExtractionContext ctx = TestContexts.ofSources(dir, NamingStrategy.springBoot(),
        """
        package fr.x;
        import org.springframework.web.reactive.function.client.WebClient;
        import org.springframework.web.client.*;
        import org.springframework.boot.web.client.RestTemplateBuilder;
        import org.springframework.http.HttpMethod;
        import java.util.List;
        public class Appels {
          private final WebClient dossiers;
          private final RestTemplate rt;
          private final RestClient rc = RestClient.builder().baseUrl("http://s-gen-pieces").build();
          public Appels(WebClient.Builder builder, RestTemplateBuilder rtb) {
            this.dossiers = builder.baseUrl("http://s-gen-dossier.gen.svc:8080/api").build();
            this.rt = rtb.rootUri("http://s-gen-compte").build();
            rt.getForObject("/init", String.class);
          }
          public Object lambda(long id) {
            return dossiers.get().uri(b -> b.path("/dossiers/{id}").queryParam("x", 1).build(id))
                .retrieve().bodyToMono(List.class).block().get(0);
          }
          public void methode(String verbe) {
            rc.method(HttpMethod.DELETE).uri("/pieces/{id}", 3).retrieve();
            rc.method(HttpMethod.valueOf(verbe)).uri("/pieces").retrieve();
          }
          public void secret() {
            rt.exchange("http://admin:Fixture-Pwd-Inline@s-gen-secret/api?token=abc&page=2", HttpMethod.GET, null, String.class);
          }
          public void local() {
            WebClient local = WebClient.builder().baseUrl("${ext.url}").build();
            local.patch().uri("/x").retrieve();
          }
        }
        """,
        """
        package fr.x;
        import feign.RequestLine;
        @org.springframework.cloud.openfeign.FeignClient(name = "s-gen-ext", path = "api")
        public interface ExtClient {
          @RequestLine("PUT /items/{id}")
          void maj(String id);
          @org.springframework.web.bind.annotation.RequestMapping("/items/{id:[0-9]+}")
          String lire(String id);
        }
        """);
    List<Call> calls = new CallExtractor(ctx).extract();
    assertThat(ids(calls)).containsExactly(
        "app:?:s-gen-pieces:/pieces@fr.x.Appels#methode",
        "app:DELETE:s-gen-pieces:/pieces/{id}@fr.x.Appels#methode",
        "app:GET:s-gen-compte:/init@fr.x.Appels#<init>",
        "app:GET:s-gen-dossier:/api/dossiers/{id}@fr.x.Appels#lambda",
        "app:GET:s-gen-ext:/api/items/{id}@fr.x.ExtClient#lire",
        "app:GET:s-gen-secret:/api@fr.x.Appels#secret",
        "app:PATCH:?:/x@fr.x.Appels#local",
        "app:PUT:s-gen-ext:/api/items/{id}@fr.x.ExtClient#maj");

    Call secret = call(calls, "app:GET:s-gen-secret:/api@fr.x.Appels#secret");
    assertThat(secret.rawUrl()).doesNotContain("Fixture-Pwd-Inline").doesNotContain("admin").doesNotContain("abc")
        .contains("page=2");
    assertThat(secret.resolvedUrl()).isEqualTo(secret.rawUrl());
    assertThat(call(calls, "app:PATCH:?:/x@fr.x.Appels#local").rawUrl()).isEqualTo("${ext.url}/x");
    assertThat(call(calls, "app:?:s-gen-pieces:/pieces@fr.x.Appels#methode").method()).isNull();
    assertThat(messages(ctx, "HTTP_METHOD_UNRESOLVED")).hasSize(1);
    assertThat(call(calls, "app:GET:s-gen-ext:/api/items/{id}@fr.x.ExtClient#lire").resolvedUrl())
        .isEqualTo("http://s-gen-ext/api/items/{id}");
  }

  @Test
  void deductionsSurLesUrl() {
    assertThat(CallExtractor.path("http://h:8080")).isEqualTo("/");
    assertThat(CallExtractor.path("http://h/a/{id:\\d+}?q=1#f")).isEqualTo("/a/{id}");
    assertThat(CallExtractor.path("${base}/a")).isEqualTo("/a");
    assertThat(CallExtractor.path("${base}")).isNull();
    assertThat(CallExtractor.path("{url}x")).isNull();
    assertThat(CallExtractor.path("relatif/a")).isNull();
    assertThat(CallExtractor.targetApp("s-gen-gpp")).isEqualTo("s-gen-gpp");
    assertThat(CallExtractor.targetApp("s-ref-geo.referentiel.svc.cluster.local")).isEqualTo("s-ref-geo");
    assertThat(CallExtractor.targetApp("s-ref-geo.svc")).isEqualTo("s-ref-geo");
    assertThat(CallExtractor.targetApp("localhost")).isNull();
    assertThat(CallExtractor.targetApp("api.cafat.nc")).isNull();
    assertThat(CallExtractor.host("https://Api.X.com:443/v")).isEqualTo("api.x.com");
    assertThat(CallExtractor.host("http://${h}/v")).isNull();
    assertThat(CallExtractor.join("http://h/", "/a", "b/", "/c")).isEqualTo("http://h/a/b/c");
  }
}
