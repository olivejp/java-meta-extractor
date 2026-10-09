package fr.cafat.meta.extract.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lecture d'un fichier mapper MyBatis : une requête par {@code select|insert|update|delete}, fragments
 * {@code <include>} remplacés, balises dynamiques aplaties (toutes les branches gardées,
 * {@code <where>} et {@code <set>} rendus en WHERE et SET). Aucune DTD n'est chargée.
 */
public final class MyBatisXml {

  /** Requête d'un mapper ; {@code dynamic} si elle contient {@code ${...}} ou un fragment introuvable. */
  public record Statement(String namespace, String id, String kind, String sql, boolean dynamic, int line) {
  }

  private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete");
  private static final int MAX_INCLUDE_DEPTH = 10;

  /** Élément XML lu en flux : nom, attributs, enfants (texte ou {@code Node}), ligne. */
  private static final class Node {
    final String name;
    final Map<String, String> attributes = new LinkedHashMap<>();
    final List<Object> children = new ArrayList<>();
    final int line;

    Node(String name, int line) {
      this.name = name;
      this.line = line;
    }

    String attr(String key) {
      return attributes.get(key);
    }
  }

  private MyBatisXml() {
  }

  /**
   * Requêtes du fichier, dans l'ordre du document ; liste vide si ce n'est pas un mapper.
   *
   * @param file fichier XML MyBatis
   * @return requêtes {@code select|insert|update|delete}, fragments inclus
   * @throws IOException si le fichier est illisible
   * @throws XMLStreamException si le XML est mal formé
   */
  public static List<Statement> read(Path file) throws IOException, XMLStreamException {
    try (InputStream in = Files.newInputStream(file)) {
      return read(in);
    }
  }

  private static List<Statement> read(InputStream in) throws XMLStreamException {
    Node root = parse(in);
    if (root == null || !"mapper".equals(root.name)) {
      return List.of();
    }
    String namespace = root.attr("namespace");
    Map<String, Node> fragments = new HashMap<>();
    for (Object c : root.children) {
      if (c instanceof Node n && "sql".equals(n.name) && n.attr("id") != null) {
        fragments.put(n.attr("id"), n);
        if (namespace != null) {
          fragments.put(namespace + "." + n.attr("id"), n);
        }
      }
    }
    List<Statement> out = new ArrayList<>();
    for (Object c : root.children) {
      if (c instanceof Node n && STATEMENTS.contains(n.name) && n.attr("id") != null) {
        boolean[] dynamic = new boolean[1];
        StringBuilder sb = new StringBuilder();
        render(n, fragments, sb, dynamic, 0);
        String sql = SqlAnalyzer.normalize(sb.toString());
        if (sql.contains("${")) {
          dynamic[0] = true;
        }
        if (!sql.isEmpty()) {
          out.add(new Statement(namespace, n.attr("id"), n.name, sql, dynamic[0], n.line));
        }
      }
    }
    return out;
  }

  /**
   * Contenu {@code <script>...</script>} d'une annotation MyBatis, lu comme le corps d'une requête
   * de mapper ; null s'il n'est pas du XML bien formé.
   *
   * @param script valeur de l'annotation, commençant par {@code <script>} (balise fermante facultative)
   * @return requête unique, d'id {@code script} et de genre {@code select} ; null si XML mal formé
   */
  public static Statement script(String script) {
    String body = script.strip();
    body = body.substring("<script>".length(), body.endsWith("</script>")
        ? body.length() - "</script>".length() : body.length());
    String xml = "<mapper><select id=\"script\">" + body + "</select></mapper>";
    try {
      List<Statement> statements = read(new java.io.ByteArrayInputStream(
          xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return statements.isEmpty() ? null : statements.get(0);
    } catch (XMLStreamException e) {
      return null;
    }
  }

  private static Node parse(InputStream in) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_COALESCING, true);
    XMLStreamReader r = factory.createXMLStreamReader(in);
    Deque<Node> stack = new ArrayDeque<>();
    Node root = null;
    try {
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          Node n = new Node(r.getLocalName(), r.getLocation().getLineNumber());
          for (int i = 0; i < r.getAttributeCount(); i++) {
            n.attributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
          }
          if (stack.isEmpty()) {
            root = n;
          } else {
            stack.peek().children.add(n);
          }
          stack.push(n);
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          stack.pop();
        } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
            || event == XMLStreamConstants.SPACE) && !stack.isEmpty()) {
          stack.peek().children.add(r.getText());
        }
      }
    } finally {
      r.close();
    }
    return root;
  }

  private static void render(Node node, Map<String, Node> fragments, StringBuilder out, boolean[] dynamic,
      int depth) {
    for (Object c : node.children) {
      if (c instanceof String text) {
        out.append(text);
        continue;
      }
      Node n = (Node) c;
      switch (n.name) {
        case "include" -> {
          Node fragment = fragments.get(n.attr("refid"));
          if (fragment == null || depth >= MAX_INCLUDE_DEPTH) {
            dynamic[0] = true;
          } else {
            out.append(' ');
            render(fragment, fragments, out, dynamic, depth + 1);
            out.append(' ');
          }
        }
        case "where" -> out.append(" WHERE ").append(strip(inner(n, fragments, dynamic, depth), "AND |OR ", null))
            .append(' ');
        case "set" -> out.append(" SET ").append(strip(inner(n, fragments, dynamic, depth), null, ",")).append(' ');
        case "trim" -> out.append(' ').append(nullToEmpty(n.attr("prefix"))).append(' ')
            .append(strip(inner(n, fragments, dynamic, depth), n.attr("prefixOverrides"), n.attr("suffixOverrides")))
            .append(' ').append(nullToEmpty(n.attr("suffix"))).append(' ');
        case "foreach" -> out.append(' ').append(nullToEmpty(n.attr("open")))
            .append(inner(n, fragments, dynamic, depth)).append(nullToEmpty(n.attr("close"))).append(' ');
        case "bind", "selectKey" -> {
          // pas de SQL dans la requête principale
        }
        default -> {
          out.append(' ');
          render(n, fragments, out, dynamic, depth);
          out.append(' ');
        }
      }
    }
  }

  private static String inner(Node n, Map<String, Node> fragments, boolean[] dynamic, int depth) {
    StringBuilder sb = new StringBuilder();
    render(n, fragments, sb, dynamic, depth);
    return SqlAnalyzer.normalize(sb.toString());
  }

  /** Retire les préfixes et suffixes MyBatis ({@code AND |OR }, {@code ,}), sans tenir compte de la casse. */
  private static String strip(String text, String prefixes, String suffixes) {
    String s = text.strip();
    if (prefixes != null) {
      for (String p : prefixes.split("\\|")) {
        String t = p.strip();
        if (!t.isEmpty() && s.toUpperCase(Locale.ROOT).startsWith(t.toUpperCase(Locale.ROOT))
            && (s.length() == t.length() || !Character.isLetterOrDigit(s.charAt(t.length())))) {
          s = s.substring(t.length()).strip();
          break;
        }
      }
    }
    if (suffixes != null) {
      for (String p : suffixes.split("\\|")) {
        String t = p.strip();
        if (!t.isEmpty() && s.toUpperCase(Locale.ROOT).endsWith(t.toUpperCase(Locale.ROOT))) {
          s = s.substring(0, s.length() - t.length()).strip();
          break;
        }
      }
    }
    return s;
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  /**
   * Vrai si le contenu XML déclare un mapper MyBatis (lecture rapide, avant analyse).
   *
   * @param content texte du fichier XML
   * @return vrai si le texte contient une balise {@code <mapper>}
   */
  public static boolean isMapper(String content) {
    return MAPPER.matcher(content).find();
  }

  private static final Pattern MAPPER = Pattern.compile("<mapper[\\s>]");
}
