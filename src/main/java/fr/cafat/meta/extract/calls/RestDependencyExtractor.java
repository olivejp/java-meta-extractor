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
 * Dépendances REST déclarées selon la convention CAFAT :
 *
 * <pre>
 * nc.cafat.rest-dependency:
 *   springboot.host: https://api…        # hôte par type de cible
 *   s-gen-geo:
 *     service: /s-gen-geo-
 *     version: 5.0
 *     type: springboot
 *     path:
 *       pays: /pays/%s
 * </pre>
 *
 * <p>Une URL d'appel vaut {@code <type>.host + service + version + path.<cle>}. L'application cible
 * est le dernier segment de {@code service} sans tirets ni barres de bord, sinon le nom de la
 * dépendance. Une entrée sans {@code service} ni {@code path} (ex. {@code springboot.host}) n'est
 * pas une dépendance.
 */
public final class RestDependencyExtractor {

  /** Préfixe de la convention, pour une recherche relâchée dans la configuration. */
  public static final String PREFIX = "nc.cafat.rest-dependency.";

  private static final Pattern KEY = Pattern.compile("^nc\\.cafat\\.rest[-_]?dependency\\.([^.]+)\\.(.+)$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern REFERENCE = Pattern.compile(
      "nc\\.cafat\\.rest[-_]?dependency\\.([A-Za-z0-9_-]+)\\.(?:service|version|type|path\\.([^}]+))\\}",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern HOST_KEY = Pattern.compile("^nc\\.cafat\\.rest[-_]?dependency\\.[A-Za-z0-9_-]+\\.host$",
      Pattern.CASE_INSENSITIVE);
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
    for (ConfigEntry e : config.entries()) {
      Matcher m = KEY.matcher(e.key());
      if (m.matches()) {
        byName.computeIfAbsent(m.group(1), k -> new TreeMap<>()).put(m.group(2), e);
      }
    }
    Set<String> calledNames = new HashSet<>();
    Set<String> calledPaths = new HashSet<>();
    for (Call c : calls) {
      Matcher m = REFERENCE.matcher(c.rawUrl() == null ? "" : c.rawUrl());
      while (m.find()) {
        calledNames.add(Config.normalize(m.group(1)));
        if (m.group(2) != null) {
          calledPaths.add(Config.normalize(m.group(1)) + "\u0000" + Config.normalize(m.group(2)));
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
      String serviceValue = value(service);
      String version = value(child(children, "version"));
      String type = value(child(children, "type"));
      String basePath = serviceValue == null ? null : serviceValue + (version == null ? "" : version);
      String host = type == null ? null : Secrets.sanitizeUrl(config.get(PREFIX + type + ".host"));
      List<RestDependencyPath> declared = new ArrayList<>();
      paths.forEach((pathName, e) -> {
        String template = value(e);
        String full = basePath == null || template == null ? null
            : CallExtractor.path(CallExtractor.formatVariables(basePath + template));
        declared.add(new RestDependencyPath(pathName, template, full,
            calledPaths.contains(Config.normalize(name) + "\u0000" + Config.normalize(pathName)), source(e)));
      });
      ConfigEntry first = service != null ? service : children.values().iterator().next();
      out.add(new RestDependency(ctx.appId() + ":rest-dependency:" + name, name, targetApp(config, name), type,
          serviceValue, version, basePath, host, calledNames.contains(Config.normalize(name)), declared,
          source(first)));
    }
    return out;
  }

  /**
   * Nom de la dépendance citée par une URL brute ({@code ${nc.cafat.rest-dependency.<nom>.service}}…).
   *
   * @param rawUrl URL brute d'un appel ; null accepté
   * @return nom de la première dépendance citée ; null si aucune
   */
  static String referenced(String rawUrl) {
    Matcher m = REFERENCE.matcher(rawUrl == null ? "" : rawUrl);
    return m.find() ? m.group(1) : null;
  }

  /**
   * Vrai si les seules clés manquantes sont des hôtes de la convention ({@code <type>.host}), propres
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
   * @param name nom de la dépendance sous {@code nc.cafat.rest-dependency}
   * @return nom de l'application cible
   */
  static String targetApp(Config config, String name) {
    String service = config.get(PREFIX + name + ".service");
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
