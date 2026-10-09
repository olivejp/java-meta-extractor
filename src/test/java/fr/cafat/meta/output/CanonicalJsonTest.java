package fr.cafat.meta.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import fr.cafat.meta.model.Source;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

  private static JsonNode tree(String json) throws Exception {
    return CanonicalJson.mapper().readTree(json);
  }

  @Test
  void formeCanoniqueOctetParOctet() throws Exception {
    JsonNode t = tree("{\"z\":1,\"a\":{\"y\":[],\"b\":{},\"c\":[1,{\"k\":\"Été €\"}]},\"m\":null}");

    String out = new String(CanonicalJson.write(t), StandardCharsets.UTF_8);

    assertThat(out).isEqualTo("""
        {
          "a": {
            "b": {},
            "c": [
              1,
              {
                "k": "Été €"
              }
            ],
            "y": []
          },
          "m": null,
          "z": 1
        }
        """);
    assertThat(out).doesNotContain("\r").endsWith("}\n");
  }

  @Test
  void clesTrieesRecursivementParPointDeCode() throws Exception {
    JsonNode sorted = CanonicalJson.sortKeys(tree("{\"b\":[{\"y\":1,\"x\":2}],\"B\":1,\"a\":0}"));

    assertThat(sorted.toString()).isEqualTo("{\"B\":1,\"a\":0,\"b\":[{\"x\":2,\"y\":1}]}");
  }

  @Test
  void formeCompacteIndependanteDeLOrdre() throws Exception {
    Map<String, Object> m1 = new LinkedHashMap<>();
    m1.put("b", 1);
    m1.put("a", List.of(2));
    Map<String, Object> m2 = new LinkedHashMap<>();
    m2.put("a", List.of(2));
    m2.put("b", 1);

    assertThat(CanonicalJson.compact(m1)).isEqualTo("{\"a\":[2],\"b\":1}").isEqualTo(CanonicalJson.compact(m2));
    assertThat(CanonicalJson.compact(tree("{\"b\":1,\"a\":2}"))).isEqualTo("{\"a\":2,\"b\":1}");
  }

  @Test
  void recordsSerialisesAvecLesNomsDuContrat() {
    JsonNode t = CanonicalJson.toTree(Source.of("nc.C", "C.java", 3));

    assertThat(t.toString()).isEqualTo("{\"class\":\"nc.C\",\"file\":\"C.java\",\"line\":3}");
    assertThat(CanonicalJson.asMap(Source.file("f.yml", null)))
        .containsEntry("class", null).containsEntry("file", "f.yml").containsEntry("line", null);
  }

  @Test
  void empreintesSha1() {
    assertThat(Hashes.sha1("abc")).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
    assertThat(Hashes.sha1("")).isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709");
    assertThat(Hashes.sha1("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(Hashes.sha1("abc"));
    assertThat(Hashes.sha1("é")).isEqualTo(Hashes.sha1("é".getBytes(StandardCharsets.UTF_8)));
  }
}
