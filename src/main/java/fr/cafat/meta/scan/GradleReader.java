package fr.cafat.meta.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture textuelle (sans exécution) des builds Gradle : settings (projets inclus, nom racine),
 * version, plugins war/ear/Spring Boot, dépendances {@code project(":x")}.
 */
public final class GradleReader {

  private static final Pattern INCLUDE = Pattern.compile("include\\s*\\(?([^)\\n]*)\\)?");
  private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
  private static final Pattern ROOT_NAME = Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
  private static final Pattern VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']");
  private static final Pattern PROJECT_DEP = Pattern.compile("project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?[\"']([^\"']+)[\"']");
  private static final Pattern ARCHIVE_NAME = Pattern.compile("archive(?:Base|File)?Name\\s*(?:\\.set\\s*\\(|=)\\s*[\"']([^\"']+)[\"']");

  private final Path root;

  public GradleReader(Path root) {
    this.root = root;
  }

  public static boolean isGradle(Path root) {
    return Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))
        || Files.isRegularFile(root.resolve("settings.gradle")) || Files.isRegularFile(root.resolve("settings.gradle.kts"));
  }

  public List<Module> read() throws IOException {
    List<Module> out = new ArrayList<>();
    String settings = readFirst(root.resolve("settings.gradle.kts"), root.resolve("settings.gradle"));
    String rootName = root.getFileName().toString();
    List<String> includes = new ArrayList<>();
    if (settings != null) {
      Matcher rn = ROOT_NAME.matcher(settings);
      if (rn.find()) {
        rootName = rn.group(1);
      }
      Matcher inc = INCLUDE.matcher(settings);
      while (inc.find()) {
        Matcher q = QUOTED.matcher(inc.group(1));
        while (q.find()) {
          includes.add(q.group(1).startsWith(":") ? q.group(1) : ":" + q.group(1));
        }
      }
    }
    String rootVersion = null;
    String rootBuild = readFirst(root.resolve("build.gradle.kts"), root.resolve("build.gradle"));
    if (rootBuild != null) {
      Matcher v = VERSION.matcher(rootBuild);
      rootVersion = v.find() ? v.group(1) : null;
    }
    out.add(module(":", rootName, root, rootVersion, includes));
    for (String inc : includes) {
      Path dir = root.resolve(inc.substring(1).replace(':', '/'));
      String name = inc.substring(inc.lastIndexOf(':') + 1);
      out.add(module(inc, name, dir, rootVersion, List.of()));
    }
    return out;
  }

  private Module module(String key, String name, Path dir, String defaultVersion, List<String> children)
      throws IOException {
    Path kts = dir.resolve("build.gradle.kts");
    Path groovy = dir.resolve("build.gradle");
    Path file = Files.isRegularFile(kts) ? kts : groovy;
    String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    Matcher v = VERSION.matcher(text);
    String version = v.find() ? v.group(1) : defaultVersion;
    String packaging = "jar";
    if (text.matches("(?s).*(\\bid\\s*\\(?\\s*[\"']war[\"']|apply\\s+plugin\\s*:\\s*[\"']war[\"']|\\bwar\\b\\s*$).*")
        || text.contains("`war`")) {
      packaging = "war";
    }
    if (text.matches("(?s).*(\\bid\\s*\\(?\\s*[\"']ear[\"']|apply\\s+plugin\\s*:\\s*[\"']ear[\"']).*") || text.contains("`ear`")) {
      packaging = "ear";
    }
    boolean boot = text.contains("org.springframework.boot") && !text.matches("(?s).*org\\.springframework\\.boot[\"']?\\)?\\s*version[^\\n]*apply\\s*\\(?\\s*false.*");
    List<String> deps = new ArrayList<>();
    Matcher d = PROJECT_DEP.matcher(text);
    while (d.find()) {
      deps.add(d.group(1).startsWith(":") ? d.group(1) : ":" + d.group(1));
    }
    Matcher an = ARCHIVE_NAME.matcher(text);
    String finalName = an.find() ? an.group(1) : null;
    String rel = root.relativize(dir).toString().replace('\\', '/');
    String buildFile = Files.isRegularFile(file) ? root.relativize(file).toString().replace('\\', '/') : null;
    return new Module(key, name, version, dir, rel, packaging, finalName, List.copyOf(deps),
        List.copyOf(children), boot, buildFile);
  }

  private static String readFirst(Path... candidates) throws IOException {
    for (Path p : candidates) {
      if (Files.isRegularFile(p)) {
        return Files.readString(p, StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
