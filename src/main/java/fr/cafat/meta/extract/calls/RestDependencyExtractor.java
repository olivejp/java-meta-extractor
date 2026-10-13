package fr.cafat.meta.extract.calls;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.ConfigEntry;
import fr.cafat.meta.config.Secrets;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.model.Call;
import fr.cafat.meta.model.RestDependency;
import fr.cafat.meta.model.RestDependencyPath;
import fr.cafat.meta.model.Source;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dépendances REST déclarées selon la convention CAFAT, sous deux formes :
 *
 * <pre>
 * nc.cafat.rest-dependency:              # configuration locale de l'application
 *   springboot.host: https://api…        # hôte par type de cible : &lt;type&gt;.host
 *   s-gen-geo:
 *     service: /s-gen-geo-
 *     version: 5.0
 *     type: springboot
 *     path:
 *       pays: /pays/%s
 *
 * cafat.config.rest-dependency:          # dépôt Spring Cloud Config (profil rest-dependency)
 *   host:
 *     springboot: https://api…           # hôte par type de cible : host.&lt;type&gt;
 *   s-gen-iam:
 *     service: /s-gen-iam-
 *     version: 5.1
 *     type: springboot
 *     path:
 *       assure:
 *         account: /assure/account       # chemin imbriqué : clé assure.account
 * </pre>
 *
 * <p>Une URL d'appel vaut {@code hôte(type) + service + version + path.<cle>}. L'application cible
 * est le dernier segment de {@code service} sans tirets ni barres de bord, sinon le nom de la
 * dépendance. Une entrée sans {@code service} ni {@code path} (ex. {@code springboot.host},
 * {@code host}) n'est pas une dépendance. Une dépendance déclarée sous les deux préfixes est
 * fusionnée, la forme {@code cafat.config} l'emportant clé par clé.
 */
public final class RestDependencyExtractor {

  /** Préfixes de la convention, du moins au plus prioritaire. */
  public static final List<String> PREFIXES = List.of("nc.cafat.rest-dependency", "cafat.config.rest-dependency");

  private static final String PREFIX_RE = "(nc\\.cafat|cafat\\.config)\\.rest[-_]?dependency";
  private static final Pattern KEY = Pattern.compile("^" + PREFIX_RE + "\\.([^.]+)\\.(.+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern REFERENCE = Pattern.compile(
      PREFIX_RE + "\\.([A-Za-z0-9_-]+)\\.(?:service|version|type|path\\.([^}]+))\\}", Pattern.CASE_INSENSITIVE);
  private static final Pattern HOST_KEY = Pattern.compile(
      "^" + PREFIX_RE + "\\.(?:[A-Za-z0-9_-]+\\.host|host\\.[A-Za-z0-9_-]+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern APP_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
  private static final String PATH = "path.";

  private final ExtractionContext ctx;

  /**
   * Prépare la lecture des dépendances déclarées.
   *
   * @param ctx contexte d'extraction de l'application (configuration effective)
   */
  public RestDependencyExtractor(ExtractionContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Dépendances déclarées dans la configuration effective, marquées appelées si une URL brute
   * d'appel les cite.
   *
   * @param calls appels sortants extraits du code
   * @return dépendances triées par nom ; liste vide si la convention n'est pas utilisée
   */
  public List<RestDependency> extract(List<Call> calls) {
    Config config = ctx.config();
    Map<String, Map<String, ConfigEntry>> byName = new TreeMap<>();
    Map<String, String> prefixOf = new TreeMap<>();
    for (String prefix : PREFIXES) {
      for (ConfigEntry e : config.entries()) {
        Matcher m = KEY.matcher(e.key());
        if (m.matches() && prefix(m.group(1)).equals(prefix)) {
          byName.computeIfAbsent(m.group(2), k -> new TreeMap<>()).put(m.group(3), e);
          if (Config.normalize(m.group(3)).equals("service") || !prefixOf.containsKey(m.group(2))) {
            prefixOf.put(m.group(2), prefix);
          }
        }
      }
    }
    Set<String> calledNames = new HashSet<>();
    Set<String> calledPaths = new HashSet<>();
    for (Call c : calls) {
      Matcher m = REFERENCE.matcher(c.rawUrl() == null ? "" : c.rawUrl());
      while (m.find()) {
        calledNames.add(Config.normalize(m.group(2)));
        if (m.group(3) != null) {
          calledPaths.add(Config.normalize(m.group(2)) + "\u0000" + Config.normalize(m.group(3)));
        }
      }
    }

    List<RestDependency> out = new ArrayList<>();
    for (Map.Entry<String, Map<String, ConfigEntry>> d : byName.entrySet()) {
      String name = d.getKey();
      Map<String, ConfigEntry> children = d.getValue();
      ConfigEntry service = child(children, "service");
      Map<String, ConfigEntry> paths = new TreeMap<>();
      children.forEach((k, e) -> {
        if (k.toLowerCase(Locale.ROOT).startsWith(PATH)) {
          paths.put(k.substring(PATH.length()), e);
        }
      });
      if (service == null && paths.isEmpty()) {
        continue;
      }
      String prefix = prefixOf.get(name);
      String serviceValue = value(service);
      String version = value(child(children, "version"));
      String type = value(child(children, "type"));
      String basePath = serviceValue == null ? null : serviceValue + (version == null ? "" : version);
      String host = type == null ? null : Secrets.sanitizeUrl(host(config, prefix, type));
      List<RestDependencyPath> declared = new ArrayList<>();
      paths.forEach((pathName, e) -> {
        String template = value(e);
        String full = basePath == null || template == null ? null
            : CallExtractor.path(CallExtractor.formatVariables(basePath + template));
        declared.add(new RestDependencyPath(pathName, template, full,
            calledPaths.contains(Config.normalize(name) + "\u0000" + Config.normalize(pathName)), source(e)));
      });
      ConfigEntry first = service != null ? service : children.values().iterator().next();
      out.add(new RestDependency(ctx.appId() + ":rest-dependency:" + name, name, prefix, targetApp(config, name),
          type, serviceValue, version, basePath, host, calledNames.contains(Config.normalize(name)), declared,
          source(first)));
    }
    return out;
  }

  /** Préfixe canonique d'une forme trouvée dans une clé ({@code nc.cafat} ou {@code cafat.config}). */
  private static String prefix(String owner) {
    return owner.toLowerCase(Locale.ROOT) + ".rest-dependency";
  }

  /** Hôte d'un type de cible : {@code <type>.host} ou {@code host.<type>}, préfixe de la dépendance d'abord. */
  private static String host(Config config, String prefix, String type) {
    List<String> prefixes = new ArrayList<>(List.of(prefix));
    PREFIXES.stream().filter(p -> !p.equals(prefix)).forEach(prefixes::add);
    for (String p : prefixes) {
      String h = config.first(p + "." + type + ".host", p + ".host." + type);
      if (h != null) {
        return h;
      }
    }
    return null;
  }

  /**
   * Nom de la dépendance citée par une URL brute ({@code ${nc.cafat.rest-dependency.<nom>.service}},
   * {@code ${cafat.config.rest-dependency.<nom>.path.<cle>}}…).
   *
   * @param rawUrl URL brute d'un appel ; null accepté
   * @return nom de la première dépendance citée ; null si aucune
   */
  static String referenced(String rawUrl) {
    Matcher m = REFERENCE.matcher(rawUrl == null ? "" : rawUrl);
    return m.find() ? m.group(2) : null;
  }

  /**
   * Vrai si les seules clés manquantes sont des hôtes de la convention ({@code <type>.host} ou
   * {@code host.<type>}), propres
   * à l'environnement et sans effet sur l'application cible ni le chemin.
   *
   * @param missing clés manquantes d'une résolution
   * @return true si non vide et composé uniquement d'hôtes
   */
  static boolean onlyHostsMissing(Set<String> missing) {
    return !missing.isEmpty() && missing.stream().allMatch(k -> HOST_KEY.matcher(k).matches());
  }

  /**
   * Application cible d'une dépendance : dernier segment de {@code service} sans {@code /} ni
   * {@code -} de bord ({@code /s-gen-geo-} → {@code s-gen-geo}), sinon le nom de la dépendance.
   *
   * @param config configuration effective
   * @param name nom de la dépendance sous l'un des {@link #PREFIXES}
   * @return nom de l'application cible
   */
  static String targetApp(Config config, String name) {
    String service = config.first(PREFIXES.get(1) + "." + name + ".service",
        PREFIXES.get(0) + "." + name + ".service");
    if (service != null) {
      String s = service.replaceAll("^[/-]+|[/-]+$", "");
      s = s.substring(s.lastIndexOf('/') + 1);
      if (APP_NAME.matcher(s).matches()) {
        return s;
      }
    }
    return name;
  }

  private static ConfigEntry child(Map<String, ConfigEntry> children, String key) {
    for (Map.Entry<String, ConfigEntry> c : children.entrySet()) {
      if (Config.normalize(c.getKey()).equals(key)) {
        return c.getValue();
      }
    }
    return null;
  }

  /** Valeur résolue, sinon brute (espaces réservés gardés) ; null si entrée absente. */
  private String value(ConfigEntry e) {
    if (e == null) {
      return null;
    }
    String v = ctx.config().get(e.key());
    if (v != null) {
      return v;
    }
    return Secrets.isSensitiveKey(e.key()) ? Secrets.MASK : e.value();
  }

  private static Source source(ConfigEntry e) {
    return Source.file(e.file(), e.line());
  }
}
