package fr.cafat.meta.config;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Source;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chargement de la configuration Spring d'une application : bootstrap*, application.yml/yaml,
 * application.properties (prioritaire sur le YAML), documents conditionnés par profil, puis
 * application-{profil}.* pour chaque profil actif, dans l'ordre.
 *
 * <p>Profils actifs : ceux demandés ({@code --profile}), sinon ceux que l'application déclare dans
 * sa configuration de base, comme Spring au démarrage : {@code spring.profiles.include}, puis
 * {@code spring.profiles.active}, chaque profil suivi des membres de son
 * {@code spring.profiles.group.<profil>}.
 */
public final class ConfigLoader {

  /** Valeur YAML qui commence par un jeton {@code @cle@} non filtré : préfixe (clé ou tiret), valeur. */
  private static final Pattern UNFILTERED_VALUE = Pattern.compile("(?m)^(\\s*(?:-\\s+|[^#\\n:]+:\\s+))(@[A-Za-z0-9_.\\-]+@[^\\n]*?)\\s*$");

  /**
   * Configuration chargée et profils réellement appliqués.
   *
   * @param config configuration effective
   * @param profiles profils actifs, dans l'ordre : demandés, sinon déclarés par l'application
   */
  public record Loaded(Config config, List<String> profiles) {
  }

  private final Diagnostics diagnostics;
  private final Function<Path, String> relative;
  private final BiFunction<Path, String, String> buildFilter;

  /**
   * Chargeur sans filtrage des ressources par le build.
   *
   * @param diagnostics collecteur des {@code CONFIG_PARSE_ERROR} et {@code PROFILE_NOT_APPLIED}
   * @param relative chemin d'un fichier relatif au dépôt, pour les provenances
   */
  public ConfigLoader(Diagnostics diagnostics, Function<Path, String> relative) {
    this(diagnostics, relative, (file, text) -> text);
  }

  /**
   * Chargeur avec filtrage des ressources par le build.
   *
   * @param diagnostics collecteur des {@code CONFIG_PARSE_ERROR} et {@code PROFILE_NOT_APPLIED}
   * @param relative chemin d'un fichier relatif au dépôt, pour les provenances
   * @param buildFilter texte du fichier après le filtrage des ressources par le build
   *     ({@code @artifactId@}…), tel que Spring le lit dans le jar
   */
  public ConfigLoader(Diagnostics diagnostics, Function<Path, String> relative,
      BiFunction<Path, String, String> buildFilter) {
    this.diagnostics = diagnostics;
    this.relative = relative;
    this.buildFilter = buildFilter;
  }

  /**
   * Fichiers de configuration candidats (application*, bootstrap*).
   *
   * @param p fichier à tester
   * @return true si le nom commence par {@code application} ou {@code bootstrap} et finit par
   *     {@code .yml}, {@code .yaml} ou {@code .properties}
   */
  public static boolean isConfigFile(Path p) {
    String n = p.getFileName().toString();
    return (n.startsWith("application") || n.startsWith("bootstrap"))
        && (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties"));
  }

  /**
   * Configuration effective : fichiers de base, documents conditionnés par profil, puis fichiers
   * {@code -profil} de chaque profil demandé. Pour chaque nom, le premier fichier de {@code files} gagne.
   *
   * @param files fichiers de configuration du module principal (puis des dépendances, en repli)
   * @param profiles profils demandés, dans l'ordre ; liste vide : {@code PROFILE_NOT_APPLIED} si des
   *     profils existent
   * @return configuration chargée ; vide si aucun fichier lisible (fichier illisible :
   *     {@code CONFIG_PARSE_ERROR})
   */
  public Config load(List<Path> files, List<String> profiles) {
    return loadEffective(files, profiles).config();
  }

  /**
   * Configuration effective et profils actifs : demandés, sinon déclarés par l'application.
   *
   * @param files fichiers de configuration du module principal (puis des dépendances, en repli)
   * @param requested profils demandés, dans l'ordre ; liste vide : profils déclarés dans la
   *     configuration de base ({@code PROFILE_NOT_APPLIED} si aucun et que des profils existent)
   * @return configuration chargée et profils appliqués
   */
  public Loaded loadEffective(List<Path> files, List<String> requested) {
    Config config = new Config();
    List<List<ConfigEntry>> conditional = new ArrayList<>();
    List<String> conditionalProfiles = new ArrayList<>();
    for (String base : List.of("bootstrap", "application")) {
      for (String ext : List.of(".yml", ".yaml", ".properties")) {
        Path f = find(files, base + ext);
        if (f == null) {
          continue;
        }
        for (List<ConfigEntry> doc : read(f)) {
          String cond = activation(doc);
          if (cond == null) {
            doc.forEach(config::put);
          } else {
            conditional.add(doc);
            conditionalProfiles.add(cond);
          }
        }
      }
    }
    List<String> profiles = requested.isEmpty() ? declaredProfiles(config) : requested;
    for (int i = 0; i < conditional.size(); i++) {
      if (matches(conditionalProfiles.get(i), profiles)) {
        conditional.get(i).forEach(config::put);
      }
    }
    for (String profile : profiles) {
      for (String base : List.of("bootstrap", "application")) {
        for (String ext : List.of(".yml", ".yaml", ".properties")) {
          Path f = find(files, base + "-" + profile + ext);
          if (f != null) {
            for (List<ConfigEntry> doc : read(f)) {
              String cond = activation(doc);
              if (cond == null || matches(cond, profiles)) {
                doc.forEach(config::put);
              }
            }
          }
        }
      }
    }
    if (profiles.isEmpty()) {
      Set<String> available = new TreeSet<>(conditionalProfiles);
      for (Path f : files) {
        String n = f.getFileName().toString();
        if (n.startsWith("application-")) {
          available.add(n.substring("application-".length(), n.lastIndexOf('.')));
        }
      }
      String declared = config.raw("spring.profiles.active");
      if (!available.isEmpty() || declared != null) {
        diagnostics.info("PROFILE_NOT_APPLIED",
            "aucun profil demandé (--profile) ni déclaré : configuration de base seule ; profils disponibles : "
                + String.join(", ", available)
                + (declared == null ? "" : " ; spring.profiles.active non résolu : " + declared),
            null);
      }
    }
    return new Loaded(config, List.copyOf(profiles));
  }

  /**
   * Profils que l'application active elle-même : {@code spring.profiles.include}, puis
   * {@code spring.profiles.active}, chacun suivi des membres de son groupe
   * ({@code spring.profiles.group.<profil>}, dépliés récursivement), sans doublon.
   *
   * @param config configuration de base (sans les fichiers de profil)
   * @return profils dans l'ordre d'activation ; vide si rien n'est déclaré ou résolu
   */
  static List<String> declaredProfiles(Config config) {
    List<String> out = new ArrayList<>();
    for (String key : List.of("spring.profiles.include", "spring.profiles.active")) {
      for (String p : split(config.get(key))) {
        addWithGroup(config, p, out, 0);
      }
    }
    return out;
  }

  private static void addWithGroup(Config config, String profile, List<String> out, int depth) {
    if (out.contains(profile) || depth > 10) {
      return;
    }
    out.add(profile);
    for (String member : split(config.get("spring.profiles.group." + profile))) {
      addWithGroup(config, member, out, depth + 1);
    }
  }

  private static List<String> split(String value) {
    List<String> out = new ArrayList<>();
    if (value != null && !value.contains("${")) {
      for (String p : value.split(",")) {
        if (!p.isBlank()) {
          out.add(p.strip());
        }
      }
    }
    return out;
  }

  private static Path find(List<Path> files, String name) {
    for (Path f : files) {
      if (f.getFileName().toString().equals(name)) {
        return f;
      }
    }
    return null;
  }

  /**
   * Documents du fichier (un par document YAML) ; liste vide si illisible ({@code CONFIG_PARSE_ERROR}).
   * Un YAML invalide à cause d'un jeton Maven {@code @cle@} resté non filtré (valeur héritée d'un
   * parent hors du dépôt, ex. {@code @description@}) est relu avec ces valeurs entre guillemets.
   */
  List<List<ConfigEntry>> read(Path f) {
    String rel = relative.apply(f);
    try {
      String text = buildFilter.apply(f, decode(Files.readAllBytes(f)));
      String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
      if (name.endsWith(".properties")) {
        return PropertiesParser.parse(text, rel);
      }
      try {
        return YamlFlattener.parse(text, rel);
      } catch (RuntimeException e) {
        String quoted = UNFILTERED_VALUE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
            m.group(1) + "\"" + m.group(2).replace("\\", "\\\\").replace("\"", "\\\"") + "\""));
        if (quoted.equals(text)) {
          throw e;
        }
        return YamlFlattener.parse(quoted, rel);
      }
    } catch (IOException | RuntimeException e) {
      diagnostics.error("CONFIG_PARSE_ERROR", "configuration illisible : " + firstLine(e.getMessage()),
          Source.file(rel, null));
      return List.of();
    }
  }

  private static String firstLine(String s) {
    if (s == null) {
      return "erreur inconnue";
    }
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }

  /**
   * UTF-8 strict, sinon ISO-8859-1.
   *
   * @param bytes contenu brut du fichier
   * @return texte décodé
   */
  public static String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      return new String(bytes, StandardCharsets.ISO_8859_1);
    }
  }

  /** Condition de profil d'un document (Boot 2.4+ ou forme historique), ou null. */
  static String activation(List<ConfigEntry> doc) {
    for (ConfigEntry e : doc) {
      if (e.key().equals("spring.config.activate.on-profile") || e.key().equals("spring.profiles")) {
        return e.value();
      }
    }
    return null;
  }

  /** Expressions simples : « a,b », « a | b », « a & b », « !a ». */
  static boolean matches(String expr, List<String> active) {
    if (expr == null) {
      return true;
    }
    for (String alt : expr.split("[,|]")) {
      boolean all = true;
      for (String term : alt.split("&")) {
        String t = term.strip().replace("(", "").replace(")", "");
        if (t.isEmpty()) {
          continue;
        }
        boolean neg = t.startsWith("!");
        String name = neg ? t.substring(1).strip() : t;
        if (active.contains(name) == neg) {
          all = false;
        }
      }
      if (all) {
        return true;
      }
    }
    return false;
  }
}
