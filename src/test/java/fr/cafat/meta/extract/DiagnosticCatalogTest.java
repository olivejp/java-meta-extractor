package fr.cafat.meta.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DiagnosticCatalogTest {

  private static final Pattern LITERAL = Pattern.compile("\\.(?:error|warning|info)\\(\\s*\"([A-Z][A-Z0-9_]*)\"");
  private static final Pattern CONSTANT_USE = Pattern.compile("\\.(?:error|warning|info)\\(\\s*([A-Z][A-Z0-9_]*)\\s*,");
  private static final Pattern CONSTANT_DEF = Pattern.compile("static final String ([A-Z][A-Z0-9_]*) = \"([A-Z][A-Z0-9_]*)\"");

  /** Tout code émis dans le code de l'extracteur doit être expliqué dans le catalogue. */
  @Test
  void chaqueCodeEmisEstDocumente() throws IOException {
    TreeSet<String> codes = new TreeSet<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String text = Files.readString(f);
        Map<String, String> constants = new TreeMap<>();
        Matcher d = CONSTANT_DEF.matcher(text);
        while (d.find()) {
          constants.put(d.group(1), d.group(2));
        }
        Matcher m = LITERAL.matcher(text);
        while (m.find()) {
          codes.add(m.group(1));
        }
        Matcher u = CONSTANT_USE.matcher(text);
        while (u.find()) {
          if (constants.containsKey(u.group(1))) {
            codes.add(constants.get(u.group(1)));
          }
        }
      }
    }
    assertThat(codes).hasSizeGreaterThan(15);
    assertThat(codes).allSatisfy(c -> assertThat(DiagnosticCatalog.has(c)).as("code non documenté : " + c).isTrue());
    assertThat(DiagnosticCatalog.has(DiagnosticCatalog.STEP_FAILED)).isTrue();
  }

  @Test
  void echecInterneDecritAvecSonEmplacement() {
    Diagnostics diags = new Diagnostics();
    try {
      Object o = null;
      o.hashCode();
    } catch (NullPointerException e) {
      diags.failure("appels REST sortants", e);
    }
    assertThat(diags.all()).singleElement().satisfies(d -> {
      assertThat(d.level()).isEqualTo(Diagnostics.ERROR);
      assertThat(d.code()).isEqualTo(DiagnosticCatalog.STEP_FAILED);
      assertThat(d.message()).startsWith("appels REST sortants : NullPointerException")
          .contains("(à DiagnosticCatalogTest.java:")
          .contains("DiagnosticCatalogTest.echecInterneDecritAvecSonEmplacement)");
    });
    assertThat(diags.failures()).hasSize(1);
  }
}
