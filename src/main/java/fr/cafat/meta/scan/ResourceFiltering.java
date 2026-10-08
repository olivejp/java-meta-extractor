package fr.cafat.meta.scan;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtrage des ressources par le maven-resources-plugin : au build, les propriétés connues du
 * projet remplacent leurs jetons ({@code @cle@}, et {@code ${cle}} hors parent Spring Boot). Les
 * jetons inconnus restent tels quels, comme dans Maven.
 *
 * @param properties propriétés du build (pom, {@code project.*}, {@code parsedVersion.*}, filtres)
 * @param defaultDelimiters {@code ${cle}} filtré en plus de {@code @cle@} ; faux sous
 *     spring-boot-starter-parent, qui ne garde que {@code @}
 * @param resources déclarations de ressources, dans l'ordre ; la dernière qui couvre un fichier décide
 */
public record ResourceFiltering(Map<String, String> properties, boolean defaultDelimiters,
    List<Resource> resources) {

  public static final ResourceFiltering NONE = new ResourceFiltering(Map.of(), true, List.of());

  /** Répertoire de ressources, ses motifs Ant (relatifs au répertoire) et son filtrage. */
  public record Resource(Path dir, boolean filtering, List<String> includes, List<String> excludes) {

    boolean covers(Path file) {
      if (!file.startsWith(dir)) {
        return false;
      }
      String rel = dir.relativize(file).toString().replace('\\', '/');
      boolean included = includes.isEmpty() || includes.stream().anyMatch(p -> ant(p, rel));
      return included && excludes.stream().noneMatch(p -> ant(p, rel));
    }
  }

  private static final Pattern AT_TOKEN = Pattern.compile("@([A-Za-z0-9_.\\-]+)@");
  private static final Pattern DOLLAR_TOKEN = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)}");

  /** Vrai si le fichier est copié avec filtrage. */
  public boolean filters(Path file) {
    boolean filtered = false;
    for (Resource r : resources) {
      if (r.covers(file)) {
        filtered = r.filtering();
      }
    }
    return filtered;
  }

  /** Texte du fichier tel que Maven le place dans le jar. */
  public String apply(Path file, String text) {
    if (!filters(file)) {
      return text;
    }
    String out = replace(AT_TOKEN, text);
    return defaultDelimiters ? replace(DOLLAR_TOKEN, out) : out;
  }

  private String replace(Pattern token, String text) {
    Matcher m = token.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String v = properties.get(m.group(1));
      m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** Motif Ant : {@code **}{@code /} couvre zéro ou plusieurs répertoires. */
  static boolean ant(String pattern, String path) {
    String p = pattern.strip().replace('\\', '/');
    if (p.endsWith("/")) {
      p = p + "**";
    }
    StringBuilder re = new StringBuilder();
    for (int i = 0; i < p.length(); i++) {
      char c = p.charAt(i);
      if (p.startsWith("**/", i)) {
        re.append("(?:.*/)?");
        i += 2;
      } else if (p.startsWith("**", i)) {
        re.append(".*");
        i += 1;
      } else if (c == '*') {
        re.append("[^/]*");
      } else if (c == '?') {
        re.append("[^/]");
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return path.matches(re.toString());
  }
}
