package fr.cafat.meta.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.cafat.meta.TestContexts;
import fr.cafat.meta.output.CanonicalJson;
import fr.cafat.meta.output.Hashes;
import fr.cafat.meta.output.SchemaValidator;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bout en bout : CLI, codes de sortie, fichiers de référence, déterminisme, secrets, schéma. */
class MainTest {

  static final Path GOLDEN = Path.of("src/test/resources/golden").toAbsolutePath();
  static final String SPRING = TestContexts.fixture("fixture-spring").toString();
  static final String JBOSS = TestContexts.fixture("fixture-jboss-multi").toString();

  record Run(int exit, String err, Map<String, byte[]> files) {
  }

  static Run run(String... args) throws IOException {
    StringWriter err = new StringWriter();
    int exit = Main.execute(new PrintWriter(err, true), args);
    Map<String, byte[]> files = new TreeMap<>();
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals("--out") && Files.isDirectory(Path.of(args[i + 1]))) {
        try (Stream<Path> s = Files.list(Path.of(args[i + 1]))) {
          for (Path f : s.sorted().toList()) {
            files.put(f.getFileName().toString(), Files.readAllBytes(f));
          }
        }
      }
    }
    return new Run(exit, err.toString(), files);
  }

  @Test
  void fixturesConformesAuxFichiersDeReference(@TempDir Path out) throws IOException {
    Run spring = run("--repo", SPRING, "--commit", "abc1234", "--out", out.toString());
    Run jboss = run("--repo", JBOSS, "--commit", "abc1234", "--out", out.toString());
    assertThat(spring.exit()).as(spring.err()).isZero();
    assertThat(jboss.exit()).as(jboss.err()).isZero();
    assertThat(jboss.files().keySet()).containsExactly("jboss-admin.json", "jboss-ear.json", "s-gen-fixture.json");
    boolean update = Boolean.getBoolean("golden.update");
    for (Map.Entry<String, byte[]> f : jboss.files().entrySet()) {
      Path golden = GOLDEN.resolve(f.getKey());
      if (update) {
        Files.createDirectories(GOLDEN);
        Files.write(golden, f.getValue());
      }
      assertThat(golden).as("fichier de référence manquant : relancer avec -Dgolden.update=true").exists();
      assertThat(new String(f.getValue(), StandardCharsets.UTF_8)).as(f.getKey())
          .isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
    }
    assertThat(spring.err()).contains("s-gen-fixture : ").contains("entités");
  }

  @Test
  void deuxExecutionsDonnentLesMemesOctets(@TempDir Path a, @TempDir Path b) throws IOException {
    Run first = run("--repo", JBOSS, "--commit", "abc1234", "--out", a.toString());
    Run second = run("--repo", JBOSS, "--commit", "abc1234", "--out", b.toString());
    Run spring1 = run("--repo", SPRING, "--commit", "abc1234", "--out", a.toString());
    Run spring2 = run("--repo", SPRING, "--commit", "abc1234", "--out", b.toString());
    assertThat(hashes(spring1)).isEqualTo(hashes(spring2));
    assertThat(hashes(first)).isEqualTo(hashes(second)).hasSize(2);
    for (byte[] content : spring2.files().values()) {
      String s = new String(content, StandardCharsets.UTF_8);
      assertThat(s).doesNotContain("\r").endsWith("}\n").startsWith("{\n  \"");
    }
  }

  private static Map<String, String> hashes(Run r) {
    Map<String, String> out = new TreeMap<>();
    r.files().forEach((k, v) -> out.put(k, Hashes.sha1(v)));
    return out;
  }

  private static final Pattern SENSITIVE_LINE = Pattern.compile(
      "(?im)^\\s*[\\w.\\[\\]-]*(?:password|passwd|pwd|secret|token|username|user|credentials?)[\\w.\\[\\]-]*\\s*[:=]\\s*(.+?)\\s*$");
  private static final Pattern SENSITIVE_PARAM = Pattern.compile("(?i)[;?&:](?:user|password|pwd)=([^;&\\s\"']+)");
  private static final Pattern DEFAULT_VALUE = Pattern.compile("\\$\\{[^:}]+:([^}]+)}");

  /** Valeurs sensibles présentes dans la configuration des fixtures (jamais affichées). */
  static List<String> secrets() throws IOException {
    List<String> out = new ArrayList<>(List.of("Fixture-Pwd-", "Fixture-Token-123", "GPPUSR", "gpp_owner"));
    try (Stream<Path> s = Files.walk(TestContexts.FIXTURES)) {
      for (Path f : s.filter(p -> p.toString().matches(".*\\.(ya?ml|properties|xml)$")).sorted().toList()) {
        String text = Files.readString(f, StandardCharsets.UTF_8);
        for (Pattern p : List.of(SENSITIVE_LINE, SENSITIVE_PARAM)) {
          Matcher m = p.matcher(text);
          while (m.find()) {
            String v = m.group(1).replaceAll("^[\"']|[\"']$", "");
            Matcher d = DEFAULT_VALUE.matcher(v);
            if (d.find()) {
              v = d.group(1);
            }
            if (v.length() >= 4 && !v.startsWith("${") && !v.startsWith("jdbc:")) {
              out.add(v);
            }
          }
        }
      }
    }
    return out;
  }

  @Test
  void aucunSecretDansLaSortie(@TempDir Path out) throws IOException {
    run("--repo", SPRING, "--out", out.toString(), "--profile", "dev,prod");
    Run r = run("--repo", JBOSS, "--out", out.toString());
    List<String> secrets = secrets();
    assertThat(secrets).hasSizeGreaterThan(4);
    List<String> leaks = new ArrayList<>();
    for (Map.Entry<String, byte[]> f : r.files().entrySet()) {
      String content = new String(f.getValue(), StandardCharsets.UTF_8);
      for (int i = 0; i < secrets.size(); i++) {
        if (content.contains(secrets.get(i))) {
          leaks.add(f.getKey() + " contient la valeur sensible n°" + i);
        }
      }
    }
    // Message sans la valeur elle-même.
    assertThat(leaks).isEmpty();
    assertThat(r.files()).isNotEmpty();
  }

  @Test
  void sortiesConformesAuSchema(@TempDir Path out) throws IOException {
    Run r = run("--repos-dir", TestContexts.FIXTURES.toString(), "--out", out.toString());
    assertThat(r.exit()).as(r.err()).isZero();
    assertThat(r.files().keySet()).containsExactly("jboss-admin.json", "jboss-ear.json", "s-gen-fixture.json");
    for (byte[] content : r.files().values()) {
      JsonNode tree = CanonicalJson.mapper().readTree(content);
      assertThat(SchemaValidator.validate(tree)).isEmpty();
    }
    ObjectNode broken = (ObjectNode) CanonicalJson.mapper().readTree(r.files().get("jboss-ear.json"));
    ((ObjectNode) broken.get("application")).remove("name");
    ((ObjectNode) broken.get("entities").get(0)).put("kind", "table");
    assertThat(SchemaValidator.validate(broken)).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void codesDeSortie(@TempDir Path out, @TempDir Path repo) throws IOException {
    assertThat(run("--out", out.toString()).exit()).isEqualTo(Main.EXIT_USAGE);
    assertThat(run("--repo", out.resolve("absent").toString(), "--out", out.toString()).exit())
        .isEqualTo(Main.EXIT_USAGE);
    assertThat(run("--repo", SPRING).exit()).isEqualTo(Main.EXIT_USAGE);
    assertThat(run("--repo", SPRING, "--repos-dir", JBOSS, "--out", out.toString()).exit())
        .isEqualTo(Main.EXIT_USAGE);
    assertThat(run("--repos-dir", TestContexts.FIXTURES.toString(), "--commit", "x", "--out", out.toString())
        .exit()).isEqualTo(Main.EXIT_USAGE);

    Run warnings = run("--repo", JBOSS, "--out", out.toString(), "--fail-on-warning");
    assertThat(warnings.exit()).isEqualTo(Main.EXIT_DIAGNOSTIC);

    Files.writeString(repo.resolve("pom.xml"), """
        <project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>casse</artifactId>
        <version>1</version></project>
        """);
    Path src = Files.createDirectories(repo.resolve("src/main/java/x"));
    Files.writeString(src.resolve("Ok.java"), "package x; public class Ok { }\n");
    Files.writeString(src.resolve("Casse.java"), "package x; public class Casse { void f( { }\n");
    Path outCasse = out.resolve("casse");
    Run broken = run("--repo", repo.toString(), "--out", outCasse.toString(), "--app-name", "app-casse");
    assertThat(broken.exit()).as(broken.err()).isEqualTo(Main.EXIT_DIAGNOSTIC);
    assertThat(broken.files()).containsOnlyKeys("app-casse.json");
    JsonNode tree = CanonicalJson.mapper().readTree(broken.files().get("app-casse.json"));
    List<String> codes = new ArrayList<>();
    tree.get("diagnostics").forEach(d -> codes.add(d.get("level").asText() + " " + d.get("code").asText()));
    assertThat(new TreeSet<>(codes)).contains("error PARSE_ERROR", "warning NO_DEPLOYABLE_MODULE");
    assertThat(tree.get("application").get("id").asText()).isEqualTo("app-casse");
    // Rapport lisible : niveau, code, libellé, origine, ce qui manque, ce qu'il faut faire, emplacement
    assertThat(broken.err())
        .contains("ERREUR · PARSE_ERROR · code source non analysable · 1 occurrence")
        .contains("origine : code du dépôt analysé")
        .contains("à faire : ouvrir le fichier cité")
        .contains("- src/main/java/x/Casse.java — ")
        .contains("AVERTISSEMENT · NO_DEPLOYABLE_MODULE");
  }

  @Test
  void listeDesDiagnostics() throws IOException {
    Run r = run("--list-diagnostics");
    assertThat(r.exit()).as(r.err()).isZero();
    assertThat(r.err()).contains("URL_UNRESOLVED · URL d'appel REST non résolue").contains("à faire : ");
  }

  @Test
  void depotIntrouvableEnUneLigne(@TempDir Path out) throws IOException {
    Run r = run("--repo", out.resolve("absent").toString(), "--out", out.toString());
    assertThat(r.exit()).isEqualTo(Main.EXIT_USAGE);
    assertThat(r.err()).contains("--repo : répertoire introuvable").doesNotContain("\tat ");
  }

  @Test
  void sourcesKotlinTraduites(@TempDir Path out) throws IOException {
    Run r = run("--repo", SPRING, "--commit", "abc1234", "--out", out.toString());
    JsonNode tree = CanonicalJson.mapper().readTree(r.files().get("s-gen-fixture.json"));
    JsonNode dossier = null;
    for (JsonNode e : tree.get("entities")) {
      if (e.get("id").asText().equals("s-gen-fixture:fr.cafat.gpp.pg.domain.Dossier")) {
        dossier = e;
      }
    }
    assertThat(dossier).isNotNull();
    assertThat(dossier.get("table").asText()).isEqualTo("gpp_dossier");
    assertThat(dossier.get("schema").asText()).isEqualTo("sgengpp");
    assertThat(dossier.get("datasource").asText()).isEqualTo("pg");
    assertThat(dossier.get("source").get("file").asText()).isEqualTo("src/main/kotlin/fr/cafat/gpp/pg/domain/Dossier.kt");
    List<String> columns = new ArrayList<>();
    dossier.get("columns").forEach(c -> columns.add(c.get("field").asText() + ">" + c.get("name").asText()));
    assertThat(columns).containsExactly("histoNumero>id", "statut>statut");

    List<String> endpoints = new ArrayList<>();
    tree.get("endpoints").forEach(e -> endpoints.add(e.get("id").asText() + " " + e.get("source").get("file").asText()));
    assertThat(endpoints).contains(
        "s-gen-fixture:GET:/gpp/api/dossiers/{id} src/main/kotlin/fr/cafat/gpp/api/DossierController.kt");

    List<String> codes = new ArrayList<>();
    tree.get("diagnostics").forEach(d -> codes.add(d.get("code").asText()));
    assertThat(codes).doesNotContain("KOTLIN_SKIPPED", "RELATION_TARGET_NOT_FOUND", "PARSE_ERROR");
  }

  @Test
  void syntaxeJavaRecente(@TempDir Path repo, @TempDir Path out) throws IOException {
    Files.writeString(repo.resolve("pom.xml"), """
        <project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>recent</artifactId>
        <version>1</version><build><plugins><plugin><groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>
        """);
    Path src = Files.createDirectories(repo.resolve("src/main/java/x"));
    // Variables anonymes « _ » (Java 22) dans des lambdas, un motif et un catch.
    Files.writeString(src.resolve("RecentController.java"), """
        package x;
        import org.springframework.web.bind.annotation.*;
        @RestController
        @RequestMapping("/api/recent")
        public class RecentController {
          sealed interface Forme permits Rond, Carre { }
          record Rond(double r) implements Forme { }
          record Carre(double c) implements Forme { }
          @GetMapping("/{id}")
          public String lire(@PathVariable String id, Object o) {
            java.util.function.BiFunction<String, String, String> f = (a, _) -> a;
            if (o instanceof Rond(var _)) { return "rond"; }
            try { return f.apply(id, null); } catch (RuntimeException _) { return null; }
          }
        }
        """);
    Run r = run("--repo", repo.toString(), "--out", out.toString(), "--app-name", "recent");
    assertThat(r.exit()).as(r.err()).isZero();
    JsonNode tree = CanonicalJson.mapper().readTree(r.files().get("recent.json"));
    List<String> codes = new ArrayList<>();
    tree.get("diagnostics").forEach(d -> codes.add(d.get("code").asText()));
    assertThat(codes).doesNotContain("PARSE_ERROR");
    assertThat(tree.get("endpoints").get(0).get("id").asText()).isEqualTo("recent:GET:/api/recent/{id}");
  }

  @Test
  void nomsDeFichiers() {
    assertThat(Main.fileName("s-gen-gpp")).isEqualTo("s-gen-gpp");
    assertThat(Main.fileName("a/b c")).isEqualTo("a_b_c");
    assertThat(Main.fileName("..")).isEqualTo("_..");
  }
}
