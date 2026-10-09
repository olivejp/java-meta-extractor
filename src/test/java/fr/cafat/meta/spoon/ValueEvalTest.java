package fr.cafat.meta.spoon;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.NamingStrategy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.visitor.filter.TypeFilter;

class ValueEvalTest {

  @TempDir
  static Path dir;
  private static ExtractionContext ctx;

  private static final String URLS = """
      package demo;

      import java.util.List;
      import java.util.StringJoiner;
      import org.springframework.beans.factory.annotation.Value;
      import org.springframework.core.env.Environment;
      import org.springframework.web.util.UriComponentsBuilder;

      public class Urls {
        static final String HOST = "http://h";
        private static final String BASE = HOST + "/api";
        private final String champ = "/champ";
        @Value("${svc.url}")
        private String injecte;
        private final String parCtor;
        private final Environment env;

        public Urls(@Value("${ctor.url:http://d}") String parCtor, Environment env) {
          this.parCtor = parCtor;
          this.env = env;
        }

        String litteral() { return "abc"; }
        String constante() { return BASE + "/x"; }
        String autreClasse() { return Constantes.PREFIX + Api.PATH; }
        String champInitialise() { return champ; }
        String locale() { String a = "/a"; String b = a + "/b"; return b; }
        String localeReaffectee() { String a = "/a"; a = "/z"; return a; }
        String localeConditionnelle(boolean c) { String a = "/a"; if (c) { a = "/b"; } return a; }
        String localeConcatenee() { String a = "/a"; a += "/b"; return a; }
        String builderChaine() { return new StringBuilder("/a").append("/b").toString(); }
        String builderLocal() {
          StringBuilder sb = new StringBuilder(HOST);
          sb.append("/p");
          sb.append("/q");
          return sb.toString();
        }
        String builderLocalChaine() {
          StringBuilder sb = new StringBuilder("/r");
          sb.append("/a").append("/b").append("/c");
          sb.append("/d");
          return sb.toString();
        }
        String builderBoucle(List<String> l) {
          StringBuilder sb = new StringBuilder("/r");
          for (String s : l) {
            sb.append(s);
          }
          return sb.toString();
        }
        String bufferChaine() {
          StringBuffer sb = new StringBuffer();
          sb.append("x");
          sb.append("y");
          return sb.toString();
        }
        String joiner() { StringJoiner j = new StringJoiner(","); j.add("a"); j.add("b"); return j.toString(); }
        String format(int id) { return String.format("%s/items/%d", BASE, id); }
        String formatIndexe() { return String.format("%2$s-%1$s", "a", "b"); }
        String formatPourcent() { return String.format("100%% %s", "ok"); }
        String formatted() { return "%s/v".formatted(HOST); }
        String formatConfiguration() { return String.format(env.getProperty("svc.fmt"), "x"); }
        String join() { return String.join("/", "a", "b", "c"); }
        String joinListe() { return String.join("-", List.of("x", "y")); }
        String trim() { return "  /t  ".trim(); }
        String majuscules() { return "abc".toUpperCase(); }
        String concat() { return HOST.concat("/c"); }
        String uri() {
          return UriComponentsBuilder.fromHttpUrl(HOST).path("/v1").pathSegment("items").build().toUriString();
        }
        String valeurChamp() { return injecte + "/p"; }
        String valeurConstructeur() { return parCtor; }
        String proprieteEnv() { return env.getProperty("svc.base"); }
        String proprieteEnvDefaut() { return env.getProperty("svc.base", "http://def"); }
        String proprieteRequise() { return env.getRequiredProperty("svc.req"); }
        String proprieteCalculee() { return env.getProperty(cle("pay")); }
        String cle(String s) { return "services." + s + ".url"; }
        String proprieteInconnue(String k) { return env.getProperty(k); }
        String variableEnvironnement() { return System.getenv("HOME_URL"); }
        String proprieteSysteme() { return System.getProperty("p.x"); }
        String ternaire(boolean c) { return c ? "/a" : "/b"; }
        String ternaireIdentique(boolean c) { return c ? "/a" : "/a"; }
        String depliage() { return chemin("42"); }
        String chemin(String id) { return BASE + "/items/" + id; }
        String plusieursRetours(boolean c) { if (c) { return "a"; } return "b"; }
        String appelPlusieursRetours() { return plusieursRetours(true); }
        String recursive() { return recursive(); }
        String mutuelle() { return ping(); }
        String ping() { return pong(); }
        String pong() { return ping(); }
      }
      """;

  private static final String CONSTANTES = """
      package demo;

      public class Constantes {
        public static final String PREFIX = "http://c";
      }
      """;

  private static final String API = """
      package demo;

      public interface Api {
        String PATH = "/api-path";
      }
      """;

  private static final String PROPS = """
      package demo;

      import org.springframework.boot.context.properties.ConfigurationProperties;

      @ConfigurationProperties(prefix = "svc")
      public class Props {
        private String baseUrl = "http://d";
        private String cible;
      }
      """;

  @BeforeAll
  static void load() {
    ctx = TestContexts.ofSources(dir, NamingStrategy.springBoot(), URLS, CONSTANTES, API, PROPS);
  }

  private static CtMethod<?> method(String name) {
    return ctx.types().get("demo.Urls").getMethodsByName(name).get(0);
  }

  private static CtExpression<?> returned(String method) {
    List<CtReturn<?>> returns = method(method).getElements(new TypeFilter<>(CtReturn.class));
    return returns.get(returns.size() - 1).getReturnedExpression();
  }

  private static PartialString eval(String method) {
    return ctx.eval().eval(returned(method));
  }

  @Test
  void litterauxEtConstantes() {
    assertThat(eval("litteral").valueOrNull()).isEqualTo("abc");
    assertThat(eval("constante").valueOrNull()).isEqualTo("http://h/api/x");
    assertThat(eval("autreClasse").valueOrNull()).isEqualTo("http://c/api-path");
    assertThat(eval("champInitialise").valueOrNull()).isEqualTo("/champ");
  }

  @Test
  void variablesLocales() {
    assertThat(eval("locale").valueOrNull()).isEqualTo("/a/b");
    assertThat(eval("localeReaffectee").valueOrNull()).isEqualTo("/z");
    PartialString conditionnelle = eval("localeConditionnelle");
    assertThat(conditionnelle.render()).isEqualTo("/a");
    assertThat(conditionnelle.dynamic()).isTrue();
    PartialString concatenee = eval("localeConcatenee");
    assertThat(concatenee.render()).isEqualTo("/a/b");
    assertThat(concatenee.dynamic()).isTrue();
  }

  @Test
  void constructeursDeChaines() {
    assertThat(eval("builderChaine").valueOrNull()).isEqualTo("/a/b");
    assertThat(eval("builderLocal").valueOrNull()).isEqualTo("http://h/p/q");
    assertThat(eval("builderLocalChaine").valueOrNull()).isEqualTo("/r/a/b/c/d");
    assertThat(eval("bufferChaine").valueOrNull()).isEqualTo("xy");
    assertThat(eval("joiner").valueOrNull()).isEqualTo("a,b");
    PartialString boucle = eval("builderBoucle");
    assertThat(boucle.render()).isEqualTo("/r{s}");
    assertThat(boucle.dynamic()).isTrue();
  }

  @Test
  void formatEtJoin() {
    assertThat(eval("format").render()).isEqualTo("http://h/api/items/{id}");
    assertThat(eval("formatIndexe").valueOrNull()).isEqualTo("b-a");
    assertThat(eval("formatPourcent").valueOrNull()).isEqualTo("100% ok");
    assertThat(eval("formatted").valueOrNull()).isEqualTo("http://h/v");
    assertThat(eval("formatConfiguration").valueOrNull()).isEqualTo("${svc.fmt}");
    assertThat(eval("join").valueOrNull()).isEqualTo("a/b/c");
    assertThat(eval("joinListe").valueOrNull()).isEqualTo("x-y");
  }

  @Test
  void methodesDeChainesEtUri() {
    assertThat(eval("trim").valueOrNull()).isEqualTo("/t");
    assertThat(eval("majuscules").valueOrNull()).isEqualTo("ABC");
    assertThat(eval("concat").valueOrNull()).isEqualTo("http://h/c");
    assertThat(eval("uri").valueOrNull()).isEqualTo("http://h/v1/items");
  }

  @Test
  void injectionParValue() {
    assertThat(eval("valeurChamp").valueOrNull()).isEqualTo("${svc.url}/p");
    assertThat(eval("valeurConstructeur").valueOrNull()).isEqualTo("${ctor.url:http://d}");
  }

  @Test
  void proprietesDeConfiguration() {
    assertThat(eval("proprieteEnv").valueOrNull()).isEqualTo("${svc.base}");
    assertThat(eval("proprieteEnvDefaut").valueOrNull()).isEqualTo("${svc.base:http://def}");
    assertThat(eval("proprieteRequise").valueOrNull()).isEqualTo("${svc.req}");
    assertThat(eval("proprieteCalculee").valueOrNull()).isEqualTo("${services.pay.url}");
    assertThat(eval("variableEnvironnement").valueOrNull()).isEqualTo("${HOME_URL}");
    assertThat(eval("proprieteSysteme").valueOrNull()).isEqualTo("${p.x}");
    PartialString inconnue = eval("proprieteInconnue");
    assertThat(inconnue.isComplete()).isFalse();
    assertThat(inconnue.dynamic()).isTrue();
  }

  @Test
  void champDeConfigurationProperties() {
    CtField<?> baseUrl = ctx.types().get("demo.Props").getField("baseUrl");
    CtField<?> cible = ctx.types().get("demo.Props").getField("cible");
    assertThat(ctx.eval().evalFieldDeclaration(baseUrl).valueOrNull()).isEqualTo("${svc.base-url:http://d}");
    assertThat(ctx.eval().evalFieldDeclaration(cible).valueOrNull()).isEqualTo("${svc.cible}");
    assertThat(ctx.eval().configurationPrefix(ctx.types().get("demo.Props"))).isEqualTo("svc");
    assertThat(ctx.eval().configurationPrefix(ctx.types().get("demo.Urls"))).isNull();
  }

  @Test
  void ternaire() {
    PartialString differents = eval("ternaire");
    assertThat(differents.render()).isEqualTo("{cond}");
    assertThat(differents.dynamic()).isTrue();
    assertThat(eval("ternaireIdentique").valueOrNull()).isEqualTo("/a");
  }

  @Test
  void methodesDuDepotDepliees() {
    assertThat(eval("depliage").valueOrNull()).isEqualTo("http://h/api/items/42");
    PartialString plusieurs = eval("appelPlusieursRetours");
    assertThat(plusieurs.isComplete()).isFalse();
    assertThat(plusieurs.dynamic()).isTrue();
  }

  @Test
  void parametreLieOuInconnu() {
    CtExpression<?> retour = returned("chemin");
    CtParameter<?> id = method("chemin").getParameters().get(0);
    assertThat(ctx.eval().eval(retour).render()).isEqualTo("http://h/api/items/{id}");
    assertThat(ctx.eval().eval(retour, Map.of(id, PartialString.lit("7"))).valueOrNull())
        .isEqualTo("http://h/api/items/7");
    assertThat(ctx.eval().eval(retour, Map.of()).startsWithUnknown()).isFalse();
  }

  @Test
  void recursionBornee() {
    assertThat(eval("recursive").isComplete()).isFalse();
    assertThat(eval("mutuelle").isComplete()).isFalse();
  }

  @Test
  void expressionNullOuIncalculable() {
    assertThat(ctx.eval().eval(null).render()).isEqualTo("{null}");
    assertThat(ctx.eval().constant(null)).isNull();
    assertThat(ctx.eval().constant(returned("ternaire"))).isNull();
    assertThat(ctx.eval().constant(returned("constante"))).isEqualTo("http://h/api/x");
    assertThat(ctx.eval().evalAll(null)).isEmpty();
  }

  @Test
  void nomEnKebab() {
    assertThat(ValueEval.kebab("baseUrl")).isEqualTo("base-url");
    assertThat(ValueEval.kebab("base_url")).isEqualTo("base-url");
    assertThat(ValueEval.kebab("url")).isEqualTo("url");
  }

  @Test
  void indentationKotlin() {
    assertThat(ValueEval.trimIndent("\n    select *\n      from t\n")).isEqualTo("select *\n  from t");
    assertThat(ValueEval.trimMargin("\n   |select *\n   |from t\n")).isEqualTo("select *\nfrom t");
  }
}
