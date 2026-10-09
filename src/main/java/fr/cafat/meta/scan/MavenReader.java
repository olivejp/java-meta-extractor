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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Lecture des pom.xml d'un dépôt (module racine et sous-modules, récursivement). */
public final class MavenReader {

  private static final Pattern PROP = Pattern.compile("\\$\\{([^}]+)}");
  private static final Pattern NUMERIC_VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
  private static final List<String> BOOT_FILTERED = List.of("**/application*.yml", "**/application*.yaml",
      "**/application*.properties");
  private static final String RESOURCES = "src/main/resources";

  /** Déclaration de ressource telle qu'écrite dans un pom, résolue ensuite pour chaque module. */
  private record RawResource(String directory, boolean filtering, List<String> includes, List<String> excludes) {
  }

  /**
   * Ce qu'un module hérite de son parent pour le filtrage des ressources.
   *
   * @param bootParent ascendance spring-boot-starter-parent (délimiteur {@code @} seul)
   * @param resources dernières ressources déclarées
   * @param parsedVersionPrefix préfixe du but build-helper:parse-version, ou null
   */
  private record Inherited(boolean bootParent, List<RawResource> resources, String parsedVersionPrefix) {
    static final Inherited NONE = new Inherited(false, List.of(), null);
  }

  private final Path root;
  private final Diagnostics diagnostics;
  private final Map<String, String> inheritedProps = new HashMap<>();

  /**
   * Prépare la lecture d'un build Maven.
   *
   * @param root racine du dépôt
   * @param diagnostics collecteur des {@code CONFIG_PARSE_ERROR}
   */
  public MavenReader(Path root, Diagnostics diagnostics) {
    this.root = root;
    this.diagnostics = diagnostics;
  }

  /**
   * Modules dans l'ordre de déclaration (parcours en profondeur depuis la racine).
   *
   * @return modules, racine en premier ; liste vide si {@code pom.xml} absent à la racine
   */
  public List<Module> read() {
    List<Module> out = new ArrayList<>();
    Path pom = root.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      read(pom, new HashMap<>(), null, Inherited.NONE, out);
    }
    return out;
  }

  private void read(Path pom, Map<String, String> parentProps, String[] parentGav, Inherited inherited,
      List<Module> out) {
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
    Inherited own = inherit(project, parent, inherited);
    Module module = new Module((groupId == null ? "" : groupId) + ":" + artifactId, artifactId,
        version == null ? null : interpolate(version, props), dir, relDir,
        packaging == null ? "jar" : packaging, finalName, List.copyOf(deps), List.copyOf(children),
        boot, rel, filtering(project, own, props, dir));
    out.add(module);
    for (String child : children) {
      Path childPom = dir.resolve(child).normalize();
      childPom = Files.isDirectory(childPom) ? childPom.resolve("pom.xml") : childPom;
      if (Files.isRegularFile(childPom)) {
        read(childPom, props, new String[] {groupId, version}, own, out);
      } else {
        diagnostics.warning("CONFIG_PARSE_ERROR", "module Maven introuvable : " + child,
            Source.file(rel, null));
      }
    }
  }

  /** Parent Spring Boot, ressources déclarées et parse-version : propres au pom ou hérités. */
  private static Inherited inherit(Element project, Element parent, Inherited inherited) {
    boolean boot = inherited.bootParent()
        || parent != null && "spring-boot-starter-parent".equals(Xml.childText(parent, "artifactId"));
    List<RawResource> resources = inherited.resources();
    Element declared = Xml.child(Xml.child(project, "build"), "resources");
    if (declared != null) {
      resources = new ArrayList<>();
      for (Element r : Xml.children(declared, "resource")) {
        String directory = Xml.childText(r, "directory");
        resources.add(new RawResource(directory == null ? RESOURCES : directory,
            "true".equals(Xml.childText(r, "filtering")), patterns(r, "includes", "include"),
            patterns(r, "excludes", "exclude")));
      }
    } else if (boot && parent != null && resources.isEmpty()) {
      // ressources de spring-boot-starter-parent : seuls les application* sont filtrés
      resources = List.of(new RawResource(RESOURCES, true, BOOT_FILTERED, List.of()),
          new RawResource(RESOURCES, false, List.of(), BOOT_FILTERED));
    }
    String prefix = inherited.parsedVersionPrefix();
    for (Element plugin : Xml.children(Xml.child(Xml.child(project, "build"), "plugins"), "plugin")) {
      if (!"build-helper-maven-plugin".equals(Xml.childText(plugin, "artifactId"))) {
        continue;
      }
      for (Element exec : Xml.descendants(plugin, "execution")) {
        for (Element goal : Xml.descendants(exec, "goal")) {
          if ("parse-version".equals(goal.getTextContent().strip())) {
            String p = Xml.childText(Xml.child(exec, "configuration"), "propertyPrefix");
            prefix = p == null ? "parsedVersion" : p;
          }
        }
      }
    }
    return new Inherited(boot, List.copyOf(resources), prefix);
  }

  private static List<String> patterns(Element resource, String list, String item) {
    List<String> out = new ArrayList<>();
    for (Element e : Xml.children(Xml.child(resource, list), item)) {
      for (String p : e.getTextContent().split(",")) {
        if (!p.isBlank()) {
          out.add(p.strip());
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * Propriétés visibles du filtrage : celles du pom, le modèle ({@code project.x}, {@code pom.x},
   * {@code x}) et {@code parsedVersion.*}. Les chemins absolus ({@code basedir}…) sont exclus : ils
   * changeraient d'une machine à l'autre.
   */
  private static ResourceFiltering filtering(Element project, Inherited own, Map<String, String> props,
      Path dir) {
    if (own.resources().stream().noneMatch(RawResource::filtering)) {
      return ResourceFiltering.NONE;
    }
    Map<String, String> values = new TreeMap<>(props);
    for (String field : List.of("name", "description")) {
      String v = Xml.childText(project, field);
      if (v != null) {
        values.put("project." + field, v);
      }
    }
    if (own.parsedVersionPrefix() != null && props.get("project.version") != null) {
      Matcher m = NUMERIC_VERSION.matcher(props.get("project.version"));
      if (m.find()) {
        String[] names = {"majorVersion", "minorVersion", "incrementalVersion"};
        for (int i = 0; i < names.length; i++) {
          long n = m.group(i + 1) == null ? 0 : Long.parseLong(m.group(i + 1));
          String next = "next" + Character.toUpperCase(names[i].charAt(0)) + names[i].substring(1);
          values.put(own.parsedVersionPrefix() + "." + names[i], Long.toString(n));
          values.put(own.parsedVersionPrefix() + "." + next, Long.toString(n + 1));
        }
      }
    }
    for (String key : List.copyOf(values.keySet())) {
      if (key.startsWith("project.") && !key.startsWith("project.parent.")) {
        String field = key.substring("project.".length());
        values.putIfAbsent("pom." + field, values.get(key));
        values.putIfAbsent(field, values.get(key));
      }
    }
    values.replaceAll((k, v) -> interpolate(v, values));
    List<ResourceFiltering.Resource> resources = new ArrayList<>();
    for (RawResource r : own.resources()) {
      String d = interpolate(r.directory().replace("${project.basedir}/", "").replace("${basedir}/", ""), values);
      resources.add(new ResourceFiltering.Resource(dir.resolve(d).normalize(), r.filtering(), r.includes(),
          r.excludes()));
    }
    return new ResourceFiltering(Map.copyOf(values), !own.bootParent(), List.copyOf(resources));
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
