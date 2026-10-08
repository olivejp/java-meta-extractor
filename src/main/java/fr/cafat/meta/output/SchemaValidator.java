package fr.cafat.meta.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validation d'une sortie contre {@code schema/meta-extract.schema.json} (embarqué dans le JAR). */
public final class SchemaValidator {

  public static final String RESOURCE = "schema/meta-extract.schema.json";

  private static volatile JsonSchema schema;

  private SchemaValidator() {
  }

  private static JsonSchema schema() {
    JsonSchema s = schema;
    if (s == null) {
      synchronized (SchemaValidator.class) {
        s = schema;
        if (s == null) {
          try (InputStream in = SchemaValidator.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
              throw new IllegalStateException("Schéma introuvable dans le classpath : " + RESOURCE);
            }
            JsonNode node = CanonicalJson.mapper().readTree(in);
            s = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
            schema = s;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      }
    }
    return s;
  }

  /** Messages d'erreur triés ; liste vide si la sortie est conforme. */
  public static List<String> validate(JsonNode document) {
    Set<ValidationMessage> messages = schema().validate(document);
    List<String> out = new ArrayList<>();
    for (ValidationMessage m : messages) {
      out.add(m.getMessage());
    }
    out.sort(String::compareTo);
    return out;
  }
}
