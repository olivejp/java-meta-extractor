package fr.cafat.meta.config;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Source;
import fr.cafat.meta.scan.Xml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Lecture des persistence.xml (JPA 1 à 3, javax ou jakarta). */
public final class PersistenceXmlReader {

  private PersistenceXmlReader() {
  }

  public static List<PersistenceUnit> read(Path file, String relative, Path moduleDir,
      Diagnostics diagnostics) {
    List<PersistenceUnit> out = new ArrayList<>();
    Document doc;
    try {
      doc = Xml.parse(file);
    } catch (IOException e) {
      diagnostics.error("CONFIG_PARSE_ERROR", "persistence.xml illisible : " + e.getMessage(),
          Source.file(relative, null));
      return out;
    }
    for (Element pu : Xml.descendants(doc.getDocumentElement(), "persistence-unit")) {
      List<String> classes = new ArrayList<>();
      for (Element c : Xml.children(pu, "class")) {
        String v = c.getTextContent().strip();
        if (!v.isEmpty()) {
          classes.add(v);
        }
      }
      Map<String, String> props = new TreeMap<>();
      for (Element p : Xml.descendants(pu, "property")) {
        String name = p.getAttribute("name");
        if (!name.isEmpty()) {
          props.put(name, p.getAttribute("value"));
        }
      }
      String exclude = Xml.childText(pu, "exclude-unlisted-classes");
      boolean excludeUnlisted = Xml.child(pu, "exclude-unlisted-classes") != null
          && (exclude == null || exclude.equalsIgnoreCase("true"));
      out.add(new PersistenceUnit(pu.getAttribute("name").strip(),
          Xml.childText(pu, "jta-data-source"), Xml.childText(pu, "non-jta-data-source"),
          List.copyOf(classes), excludeUnlisted, props, relative, Xml.line(pu), moduleDir));
    }
    return out;
  }
}
