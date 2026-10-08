package fr.cafat.meta.extract.rest;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.model.Endpoint;
import fr.cafat.meta.scan.RepoScanner;
import fr.cafat.meta.scan.WebModule;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndpointExtractorTest {

  static Endpoint endpoint(List<Endpoint> endpoints, String id) {
    return endpoints.stream().filter(e -> e.id().equals(id)).findFirst()
        .orElseThrow(() -> new AssertionError(id + " absent : " + endpoints.stream().map(Endpoint::id).toList()));
  }

  static List<String> ids(List<Endpoint> endpoints) {
    return endpoints.stream().map(Endpoint::id).sorted().toList();
  }

  @Test
  void modulesWebDesFixtures() {
    Diagnostics diags = new Diagnostics();
    List<WebModule> spring = TestContexts.webModules(
        new RepoScanner(TestContexts.fixture("fixture-spring"), diags), diags);
    assertThat(spring).containsExactly(new WebModule("", "/gpp", "", List.of()));

    List<WebModule> jboss = TestContexts.webModules(
        new RepoScanner(TestContexts.fixture("fixture-jboss-multi"), diags), diags);
    assertThat(jboss).containsExactlyInAnyOrder(
        new WebModule("jboss-web", "/legacy", "", List.of()),
        new WebModule("jboss-admin", "/admin", "", List.of("/api")));
  }

  @Test
  void controleursSpring() {
    ExtractionContext ctx = TestContexts.ofRepoWeb(TestContexts.fixture("fixture-spring"), "s-gen-fixture",
        NamingStrategy.springBoot());
    List<Endpoint> endpoints = new EndpointExtractor(ctx).extract();
    String p = "s-gen-fixture:";
    assertThat(ids(endpoints)).containsExactly(
        p + "DELETE:/gpp/api/personnes/{id}",
        p + "GET:/gpp/api/personnes/export",
        p + "GET:/gpp/api/personnes/export.csv",
        p + "GET:/gpp/api/personnes/search",
        p + "GET:/gpp/api/personnes/{id}",
        p + "GET:/gpp/legacy/ping",
        p + "HEAD:/gpp/api/personnes/export",
        p + "HEAD:/gpp/api/personnes/export.csv",
        p + "POST:/gpp/api/personnes");

    Endpoint get = endpoint(endpoints, p + "GET:/gpp/api/personnes/{id}");
    assertThat(get.framework()).isEqualTo("spring_mvc");
    assertThat(get.method()).isEqualTo("GET");
    assertThat(get.path()).isEqualTo("/gpp/api/personnes/{id}");
    assertThat(get.handler()).isEqualTo("fr.cafat.gpp.api.PersonneController#getPersonne");
    assertThat(get.requestType()).isNull();
    assertThat(get.responseType()).isEqualTo("fr.cafat.gpp.api.dto.PersonneDto");
    assertThat(get.source().file()).isEqualTo("src/main/java/fr/cafat/gpp/api/PersonneController.java");
    assertThat(get.source().line()).isNotNull();

    Endpoint post = endpoint(endpoints, p + "POST:/gpp/api/personnes");
    assertThat(post.requestType()).isEqualTo("fr.cafat.gpp.api.dto.PersonneDto");
    assertThat(post.responseType()).isEqualTo("fr.cafat.gpp.api.dto.PersonneDto");

    assertThat(endpoint(endpoints, p + "GET:/gpp/api/personnes/search").responseType())
        .isEqualTo("java.util.List<fr.cafat.gpp.api.dto.PersonneDto>");
    assertThat(endpoint(endpoints, p + "DELETE:/gpp/api/personnes/{id}").responseType()).isNull();
    assertThat(endpoint(endpoints, p + "HEAD:/gpp/api/personnes/export.csv").responseType()).isEqualTo("byte[]");
    assertThat(endpoint(endpoints, p + "GET:/gpp/legacy/ping").handler())
        .isEqualTo("fr.cafat.gpp.api.LegacyController#ping");
  }

  @Test
  void ressourcesJaxrsMultiModules() {
    ExtractionContext ctx = TestContexts.ofRepoWeb(TestContexts.fixture("fixture-jboss-multi"), "legacy",
        NamingStrategy.jpa());
    List<Endpoint> endpoints = new EndpointExtractor(ctx).extract();
    assertThat(ids(endpoints)).containsExactly(
        "legacy:GET:/admin/api/sante",
        "legacy:GET:/legacy/rest/contrats/{id}",
        "legacy:GET:/legacy/rest/contrats/{id}/assure",
        "legacy:POST:/legacy/rest/contrats");

    Endpoint get = endpoint(endpoints, "legacy:GET:/legacy/rest/contrats/{id}");
    assertThat(get.framework()).isEqualTo("jax_rs");
    assertThat(get.handler()).isEqualTo("fr.cafat.legacy.rest.ContratResource#get");
    assertThat(get.requestType()).isNull();
    assertThat(get.responseType()).isEqualTo("fr.cafat.legacy.domain.Contrat");

    Endpoint post = endpoint(endpoints, "legacy:POST:/legacy/rest/contrats");
    assertThat(post.requestType()).isEqualTo("fr.cafat.legacy.domain.Contrat");
    assertThat(post.responseType()).isNull();

    assertThat(endpoint(endpoints, "legacy:GET:/admin/api/sante").responseType()).isEqualTo("String");
    assertThat(endpoint(endpoints, "legacy:GET:/legacy/rest/contrats/{id}/assure").handler())
        .isEqualTo("fr.cafat.legacy.rest.ContratResource#assure");
  }

  @Test
  void heritageAnyPlaceholderEtServletSpring(@TempDir Path dir) {
    ExtractionContext base = TestContexts.ofSources(dir, NamingStrategy.springBoot(),
        """
        package fr.x.api;
        import org.springframework.web.bind.annotation.*;
        @RequestMapping("/base")
        public interface ApiContrat {
          @GetMapping("/{code:[A-Z]{3}}")
          Object lire(@PathVariable String code);
          @PostMapping
          void ecrire(@RequestBody fr.x.api.Commande commande);
        }
        """,
        """
        package fr.x.api;
        public class Commande { }
        """,
        """
        package fr.x.api;
        import org.springframework.web.bind.annotation.*;
        public abstract class AbstractCrud {
          @RequestMapping("/tout")
          public String tout() { return ""; }
        }
        """,
        """
        package fr.x.api;
        import org.springframework.web.bind.annotation.*;
        @RestController
        public class ContratController extends AbstractCrud implements ApiContrat {
          public Object lire(String code) { return null; }
          public void ecrire(Commande commande) { }
          @GetMapping(Routes.INCONNU + "/x")
          public String dynamique() { return ""; }
          @GetMapping("${api.version}/v")
          public String version() { return ""; }
        }
        """,
        """
        package fr.x.api;
        import org.springframework.stereotype.Controller;
        import org.springframework.web.bind.annotation.*;
        @Controller
        @ResponseBody
        @RequestMapping("vues")
        public class VueController {
          @GetMapping
          public String index() { return ""; }
        }
        """);
    ExtractionContext ctx = TestContexts.build(base.root(), files(dir), "app", NamingStrategy.springBoot(),
        base.diagnostics(), List.of(new WebModule("", "/ctx", "/mvc", List.of())));
    List<Endpoint> endpoints = new EndpointExtractor(ctx).extract();
    assertThat(ids(endpoints)).containsExactly(
        "app:ANY:/ctx/mvc/base/tout",
        "app:GET:/ctx/mvc/base/${INCONNU}/x",
        "app:GET:/ctx/mvc/base/${api.version}/v",
        "app:GET:/ctx/mvc/base/{code}",
        "app:GET:/ctx/mvc/vues",
        "app:POST:/ctx/mvc/base");

    Endpoint lire = endpoint(endpoints, "app:GET:/ctx/mvc/base/{code}");
    assertThat(lire.handler()).isEqualTo("fr.x.api.ContratController#lire");
    assertThat(lire.source().file()).isEqualTo("fr/x/api/ContratController.java");
    assertThat(lire.responseType()).isEqualTo("Object");
    assertThat(endpoint(endpoints, "app:POST:/ctx/mvc/base").requestType()).isEqualTo("fr.x.api.Commande");
    assertThat(ctx.diagnostics().all()).filteredOn(d -> d.code().equals("ENDPOINT_PATH_UNRESOLVED")).hasSize(2);
  }

  @Test
  void jaxrsInterfaceEtSousRessource(@TempDir Path dir) {
    ExtractionContext ctx = TestContexts.ofSources(dir, NamingStrategy.jpa(),
        """
        package fr.x.rs;
        import javax.ws.rs.*;
        @ApplicationPath("api")
        public class Config extends javax.ws.rs.core.Application { }
        """,
        """
        package fr.x.rs;
        import javax.ws.rs.*;
        import javax.ws.rs.core.*;
        @Path("dossiers")
        public interface DossierApi {
          @GET @Path("{id : \\\\d+}")
          java.util.concurrent.CompletionStage<fr.x.rs.Dossier> lire(@PathParam("id") long id, @Context UriInfo info);
          @PUT
          void maj(@PathParam("id") long id, Dossier dossier);
          @Path("{id}/pieces")
          PieceResource pieces();
        }
        """,
        """
        package fr.x.rs;
        public class Dossier { }
        """,
        """
        package fr.x.rs;
        public class DossierResource implements DossierApi {
          public java.util.concurrent.CompletionStage<Dossier> lire(long id, javax.ws.rs.core.UriInfo info) { return null; }
          public void maj(long id, Dossier dossier) { }
          public PieceResource pieces() { return null; }
        }
        """);
    List<Endpoint> endpoints = new EndpointExtractor(ctx).extract();
    assertThat(ids(endpoints)).containsExactly("app:GET:/api/dossiers/{id}", "app:PUT:/api/dossiers");
    Endpoint lire = endpoint(endpoints, "app:GET:/api/dossiers/{id}");
    assertThat(lire.handler()).isEqualTo("fr.x.rs.DossierResource#lire");
    assertThat(lire.requestType()).isNull();
    assertThat(lire.responseType()).isEqualTo("fr.x.rs.Dossier");
    assertThat(endpoint(endpoints, "app:PUT:/api/dossiers").requestType()).isEqualTo("fr.x.rs.Dossier");
    assertThat(TestContexts.codes(ctx)).contains("SUBRESOURCE_LOCATOR_IGNORED");
  }

  @Test
  void normalisationDesChemins() {
    assertThat(EndpointExtractor.path("/gpp", "", "api/", "/{id:[0-9]{3}}")).isEqualTo("/gpp/api/{id}");
    assertThat(EndpointExtractor.path("", "", "", "")).isEqualTo("/");
    assertThat(EndpointExtractor.path("/", "//a//", "{ x : \\d+ }")).isEqualTo("/a/{x}");
    assertThat(EndpointExtractor.path("${base}", "{id}")).isEqualTo("/${base}/{id}");
  }

  private static List<Path> files(Path dir) {
    try (var s = java.nio.file.Files.walk(dir)) {
      return s.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
