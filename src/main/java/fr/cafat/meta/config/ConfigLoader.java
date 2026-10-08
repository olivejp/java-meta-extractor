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
import java.util.function.Function;

/**
 * Chargement de la configuration Spring d'une application : bootstrap*, application.yml/yaml,
 * application.properties (prioritaire sur le YAML), documents conditionnés par profil, puis
 * application-{profil}.* pour chaque profil demandé, dans l'ordre.
 */
public final class ConfigLoader {

  private final Diagnostics diagnostics;
  private final Function<Path, String> relative;

  public ConfigLoader(Diagnostics diagnostics, Function<Path, String> relative) {
    this.diagnostics = diagnostics;
    this.relative = relative;
  }

  /** Fichiers de configuration candidats (application*, bootstrap*). */
  public static boolean isConfigFile(Path p) {
    String n = p.getFileName().toString();
    return (n.startsWith("application") || n.startsWith("bootstrap"))
        && (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties"));
  }

  /**
   * @param files fichiers de configuration du module principal (puis des dépendances, en repli)
   * @param profiles profils demandés, dans l'ordre
   */
  public Config load(List<Path> files, List<String> profiles) {
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
            "aucun profil demandé (--profile) : configuration de base seule ; profils disponibles : "
                + String.join(", ", available)
                + (declared == null ? "" : " ; spring.profiles.active=" + declared),
            null);
      }
    }
    return config;
  }

  private static Path find(List<Path> files, String name) {
    for (Path f : files) {
      if (f.getFileName().toString().equals(name)) {
        return f;
      }
    }
    return null;
  }

  private List<List<ConfigEntry>> read(Path f) {
    String rel = relative.apply(f);
    try {
      String text = decode(Files.readAllBytes(f));
      String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
      return name.endsWith(".properties") ? PropertiesParser.parse(text, rel) : YamlFlattener.parse(text, rel);
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

  /** UTF-8 strict, sinon ISO-8859-1. */
  public static String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      return new String(bytes, StandardCharsets.ISO_8859_1);
    }
  }

  /** Condition de profil d'un document (Boot 2.4+ ou forme historique), ou null. */
  private static String activation(List<ConfigEntry> doc) {
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
