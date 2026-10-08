package fr.cafat.meta.scan;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Lecture XML hors ligne : aucune DTD ni entité externe n'est chargée. Chaque élément porte son
 * numéro de ligne ({@link #line(Element)}).
 */
public final class Xml {

  private static final String LINE = "meta.line";

  private Xml() {
  }

  public static Document parse(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return parse(new InputSource(in));
    }
  }

  public static Document parse(InputSource source) throws IOException {
    try {
      SAXParserFactory spf = SAXParserFactory.newInstance();
      spf.setNamespaceAware(false);
      spf.setValidating(false);
      spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
      spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      SAXParser parser = spf.newSAXParser();
      Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      Builder handler = new Builder(doc);
      parser.getXMLReader().setEntityResolver((p, s) -> new InputSource(new StringReader("")));
      parser.parse(source, handler);
      return doc;
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /** Ligne 1-based de l'élément, ou null. */
  public static Integer line(Element e) {
    Object v = e == null ? null : e.getUserData(LINE);
    return v instanceof Integer i ? i : null;
  }

  /** Nom local, sans préfixe d'espace de noms. */
  public static String localName(Node n) {
    String name = n.getNodeName();
    int i = name.indexOf(':');
    return i < 0 ? name : name.substring(i + 1);
  }

  /** Enfants directs de nom local donné, dans l'ordre du document. */
  public static List<Element> children(Element parent, String name) {
    List<Element> out = new ArrayList<>();
    if (parent == null) {
      return out;
    }
    NodeList nl = parent.getChildNodes();
    for (int i = 0; i < nl.getLength(); i++) {
      if (nl.item(i) instanceof Element el && (name == null || name.equals(localName(el)))) {
        out.add(el);
      }
    }
    return out;
  }

  public static Element child(Element parent, String name) {
    List<Element> c = children(parent, name);
    return c.isEmpty() ? null : c.get(0);
  }

  /** Texte d'un enfant direct, sans espaces de bord ; null si absent ou vide. */
  public static String childText(Element parent, String name) {
    Element c = child(parent, name);
    if (c == null) {
      return null;
    }
    String t = c.getTextContent().strip();
    return t.isEmpty() ? null : t;
  }

  /** Descendants de nom local donné, dans l'ordre du document. */
  public static List<Element> descendants(Element root, String name) {
    List<Element> out = new ArrayList<>();
    collect(root, name, out);
    return out;
  }

  private static void collect(Element e, String name, List<Element> out) {
    NodeList nl = e.getChildNodes();
    for (int i = 0; i < nl.getLength(); i++) {
      if (nl.item(i) instanceof Element el) {
        if (name.equals(localName(el))) {
          out.add(el);
        }
        collect(el, name, out);
      }
    }
  }

  /** Construit un DOM en mémorisant la ligne d'ouverture de chaque élément. */
  private static final class Builder extends DefaultHandler {

    private final Document doc;
    private final Deque<Node> stack = new ArrayDeque<>();
    private final StringBuilder text = new StringBuilder();
    private Locator locator;

    Builder(Document doc) {
      this.doc = doc;
      stack.push(doc);
    }

    @Override
    public void setDocumentLocator(Locator locator) {
      this.locator = locator;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
      flushText();
      Element el = doc.createElement(qName);
      for (int i = 0; i < atts.getLength(); i++) {
        el.setAttribute(atts.getQName(i), atts.getValue(i));
      }
      el.setUserData(LINE, locator == null ? null : locator.getLineNumber(), null);
      stack.peek().appendChild(el);
      stack.push(el);
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      flushText();
      stack.pop();
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      text.append(ch, start, length);
    }

    private void flushText() {
      if (!text.isEmpty()) {
        stack.peek().appendChild(doc.createTextNode(text.toString()));
        text.setLength(0);
      }
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId) {
      return new InputSource(new StringReader(""));
    }
  }
}
