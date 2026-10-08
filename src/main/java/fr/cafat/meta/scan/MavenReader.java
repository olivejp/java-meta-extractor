package fr.cafat.meta.scan;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Lecture des pom.xml d'un dépôt (module racine et sous-modules, récursivement). */
public final class MavenReader {

  private static final Pattern PROP = Pattern.compile("\\$\\{([^}]+)}");

  private final Path root;
  private final Diagnostics diagnostics;
  private final Map<String, String> inheritedProps = new HashMap<>();

  public MavenReader(Path root, Diagnostics diagnostics) {
    this.root = root;
    this.diagnostics = diagnostics;
  }

  /** Modules dans l'ordre de déclaration (parcours en profondeur depuis la racine). */
  public List<Module> read() {
    List<Module> out = new ArrayList<>();
    Path pom = root.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      read(pom, new HashMap<>(), null, out);
    }
    return out;
  }

  private void read(Path pom, Map<String, String> parentProps, String[] parentGav, List<Module> out) {
    Document doc;
    String rel = relative(pom);
    try {
      doc = Xml.parse(pom);
    } catch (IOException e) {
      diagnostics.error("CONFIG_PARSE_ERROR", "pom.xml illisible : " + e.getMessage(), Source.file(rel, null));
      return;
    }
    Element project = doc.getDocumentElement();
    Element parent = Xml.child(project, "parent");
    String groupId = Xml.childText(project, "groupId");
    String version = Xml.childText(project, "version");
    if (parent != null) {
      if (groupId == null) {
        groupId = Xml.childText(parent, "groupId");
      }
      if (version == null) {
        version = Xml.childText(parent, "version");
      }
    } else if (parentGav != null) {
      groupId = groupId == null ? parentGav[0] : groupId;
      version = version == null ? parentGav[1] : version;
    }
    String artifactId = Xml.childText(project, "artifactId");
    Map<String, String> props = new HashMap<>(parentProps);
    Element properties = Xml.child(project, "properties");
    for (Element p : Xml.children(properties, null)) {
      props.put(Xml.localName(p), p.getTextContent().strip());
    }
    props.put("project.groupId", groupId == null ? "" : groupId);
    props.put("project.artifactId", artifactId == null ? "" : artifactId);
    if (version != null) {
      props.put("project.version", interpolate(version, props));
    }
    if (parent != null && Xml.childText(parent, "version") != null) {
      props.put("project.parent.version", Xml.childText(parent, "version"));
    }
    String packaging = Xml.childText(project, "packaging");
    List<String> deps = new ArrayList<>();
    Element dependencies = Xml.child(project, "dependencies");
    for (Element d : Xml.children(dependencies, "dependency")) {
      String g = interpolate(Xml.childText(d, "groupId"), props);
      String a = interpolate(Xml.childText(d, "artifactId"), props);
      String scope = Xml.childText(d, "scope");
      if (a != null && !"test".equals(scope)) {
        deps.add((g == null ? "" : g) + ":" + a);
      }
    }
    Element build = Xml.child(project, "build");
    String finalName = interpolate(Xml.childText(build, "finalName"), props);
    boolean boot = false;
    for (Element plugin : Xml.descendants(project, "plugin")) {
      String a = Xml.childText(plugin, "artifactId");
      if ("spring-boot-maven-plugin".equals(a)) {
        boot = true;
      }
    }
    List<String> children = new ArrayList<>();
    for (Element m : Xml.children(Xml.child(project, "modules"), "module")) {
      children.add(m.getTextContent().strip());
    }
    Path dir = pom.getParent();
    String relDir = relative(dir);
    Module module = new Module((groupId == null ? "" : groupId) + ":" + artifactId, artifactId,
        version == null ? null : interpolate(version, props), dir, relDir,
        packaging == null ? "jar" : packaging, finalName, List.copyOf(deps), List.copyOf(children),
        boot, rel);
    out.add(module);
    for (String child : children) {
      Path childPom = dir.resolve(child).normalize();
      childPom = Files.isDirectory(childPom) ? childPom.resolve("pom.xml") : childPom;
      if (Files.isRegularFile(childPom)) {
        read(childPom, props, new String[] {groupId, version}, out);
      } else {
        diagnostics.warning("CONFIG_PARSE_ERROR", "module Maven introuvable : " + child,
            Source.file(rel, null));
      }
    }
  }

  /** Remplace les ${prop} connues ; les inconnues restent telles quelles. */
  static String interpolate(String value, Map<String, String> props) {
    if (value == null) {
      return null;
    }
    String current = value;
    for (int i = 0; i < 5 && current.contains("${"); i++) {
      Matcher m = PROP.matcher(current);
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        String v = props.get(m.group(1));
        m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
      }
      m.appendTail(sb);
      if (sb.toString().equals(current)) {
        break;
      }
      current = sb.toString();
    }
    return current;
  }

  private String relative(Path p) {
    String s = root.relativize(p).toString().replace('\\', '/');
    return s;
  }
}
