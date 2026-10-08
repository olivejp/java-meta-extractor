package fr.cafat.meta.output;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.Instantiatable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Sérialisation canonique : clés triées (ordre des points de code), indentation de 2 espaces,
 * séparateur {@code ": "}, conteneurs vides {@code []} / {@code {}}, fins de ligne LF, UTF-8 sans
 * échappement des caractères non ASCII, saut de ligne final.
 */
public final class CanonicalJson {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private CanonicalJson() {
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  /** Arbre JSON aux clés triées récursivement. */
  public static JsonNode toTree(Object value) {
    return sortKeys(MAPPER.valueToTree(value));
  }

  public static JsonNode sortKeys(JsonNode node) {
    if (node instanceof ObjectNode obj) {
      List<String> names = new ArrayList<>();
      for (Iterator<String> it = obj.fieldNames(); it.hasNext();) {
        names.add(it.next());
      }
      names.sort(String::compareTo);
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      for (String n : names) {
        out.set(n, sortKeys(obj.get(n)));
      }
      return out;
    }
    if (node instanceof ArrayNode arr) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode el : arr) {
        out.add(sortKeys(el));
      }
      return out;
    }
    return node;
  }

  /** Octets UTF-8 de la forme canonique, terminés par un LF. */
  public static byte[] write(JsonNode tree) {
    try {
      String s = MAPPER.writer(new Printer()).writeValueAsString(sortKeys(tree));
      return (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Forme compacte et triée, pour les empreintes et départages. */
  public static String compact(Object value) {
    try {
      return MAPPER.writeValueAsString(sortKeys(value instanceof JsonNode n ? n : MAPPER.valueToTree(value)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Map<String, Object> asMap(Object value) {
    return MAPPER.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {
    });
  }

  /** Imprimeur à indentation fixe, indépendant des valeurs par défaut de Jackson. */
  static final class Printer implements PrettyPrinter, Instantiatable<Printer> {

    private int depth;

    @Override
    public Printer createInstance() {
      return new Printer();
    }

    private void newline(JsonGenerator g) throws IOException {
      g.writeRaw('\n');
      for (int i = 0; i < depth; i++) {
        g.writeRaw("  ");
      }
    }

    @Override
    public void writeRootValueSeparator(JsonGenerator g) {
      // une seule valeur racine
    }

    @Override
    public void writeStartObject(JsonGenerator g) throws IOException {
      g.writeRaw('{');
      depth++;
    }

    @Override
    public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
      depth--;
      if (nrOfEntries > 0) {
        newline(g);
      }
      g.writeRaw('}');
    }

    @Override
    public void writeObjectEntrySeparator(JsonGenerator g) throws IOException {
      g.writeRaw(',');
      newline(g);
    }

    @Override
    public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
      g.writeRaw(": ");
    }

    @Override
    public void writeStartArray(JsonGenerator g) throws IOException {
      g.writeRaw('[');
      depth++;
    }

    @Override
    public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
      depth--;
      if (nrOfValues > 0) {
        newline(g);
      }
      g.writeRaw(']');
    }

    @Override
    public void writeArrayValueSeparator(JsonGenerator g) throws IOException {
      g.writeRaw(',');
      newline(g);
    }

    @Override
    public void beforeArrayValues(JsonGenerator g) throws IOException {
      newline(g);
    }

    @Override
    public void beforeObjectEntries(JsonGenerator g) throws IOException {
      newline(g);
    }
  }
}
