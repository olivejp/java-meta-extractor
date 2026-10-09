package fr.cafat.meta.config;

import fr.cafat.meta.extract.Diagnostics;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Dépôt Spring Cloud Config cloné en local (branche ou label déjà extrait). Sa configuration
 * s'applique par-dessus la configuration locale de l'application, comme le fait le client Spring
 * Cloud Config (propriétés distantes prioritaires).
 *
 * <p>Fichiers lus, du moins au plus prioritaire : {@code application.*}, {@code {nom}.*}, puis pour
 * chaque profil dans l'ordre {@code application-{profil}.*}, {@code {nom}-{profil}.*}. Dans chaque
 * niveau : racine du dépôt, puis répertoires de recherche dans l'ordre ; {@code .properties}
 * prioritaire sur {@code .yml}/{@code .yaml}. Sans profil demandé : profil {@code default}, comme le
 * client. Valeurs chiffrées {@code {cipher}…} : masquées.
 */
public final class CloudConfigRepo {

  /** Préfixe des provenances : fichier relatif au dépôt de configuration, pas au dépôt analysé. */
  public static final String PREFIX = "cloud-config:";

  private static final List<String> CLIENT_ARTIFACTS = List.of("spring-cloud-starter-config",
      "spring-cloud-config-client");
  private static final List<String> EXTENSIONS = List.of(".yml", ".yaml", ".properties");
  private static final int MAX_DEPTH = 6;

  private final Path root;
  private final List<String> searchPaths;

  /**
   * Dépôt de configuration.
   *
   * @param root racine du clone, obligatoire
   * @param searchPaths répertoires de recherche relatifs à la racine, comme
   *     {@code spring.cloud.config.server.git.search-paths} : {@code {application}}, {@code {profile}}
   *     et le joker {@code *} acceptés ; liste vide : racine seule
   */
  public CloudConfigRepo(Path root, List<String> searchPaths) {
    this.root = root.toAbsolutePath().normalize();
    this.searchPaths = List.copyOf(searchPaths);
  }

  /**
   * Racine du clone.
   *
   * @return chemin absolu normalisé
   */
  public Path root() {
    return root;
  }

  /**
   * Vrai si l'application est cliente de Spring Cloud Config.
   *
   * @param local configuration locale de l'application
   * @param buildFiles textes des fichiers de build (pom.xml, build.gradle[.kts]) de ses modules
   * @return true si un build déclare {@code spring-cloud-starter-config} ou
   *     {@code spring-cloud-config-client}, ou si la configuration déclare {@code spring.cloud.config.uri}
   *     ou {@code spring.config.import=configserver:…} ; false si {@code spring.cloud.config.enabled=false}
   */
  public static boolean isClient(Config local, List<String> buildFiles) {
    if ("false".equalsIgnoreCase(local.get("spring.cloud.config.enabled"))) {
      return false;
    }
    String imports = local.raw("spring.config.import");
    if (local.raw("spring.cloud.config.uri") != null || (imports != null && imports.contains("configserver:"))) {
      return true;
    }
    return buildFiles.stream().anyMatch(t -> CLIENT_ARTIFACTS.stream().anyMatch(t::contains));
  }

  /**
   * Noms sous lesquels le client demande sa configuration au serveur.
   *
   * @param local configuration locale de l'application
   * @param fallback nom utilisé si la configuration n'en donne aucun (artifactId du module principal)
   * @return {@code spring.cloud.config.name} (liste séparée par des virgules), sinon
   *     {@code spring.application.name}, sinon {@code fallback} ; jamais vide
   */
  public static List<String> names(Config local, String fallback) {
    String declared = local.get("spring.cloud.config.name");
    if (declared == null || declared.isBlank()) {
      declared = local.get("spring.application.name");
    }
    List<String> out = new ArrayList<>();
    if (declared != null && !declared.contains("${")) {
      for (String n : declared.split(",")) {
        if (!n.isBlank() && !out.contains(n.strip())) {
          out.add(n.strip());
        }
      }
    }
    return out.isEmpty() ? List.of(fallback) : out;
  }

  /**
   * Applique la configuration distante de l'application sur sa configuration locale.
   *
   * @param config configuration locale, modifiée : les clés distantes remplacent les clés locales
   * @param names noms de l'application ({@link #names}), dans l'ordre de priorité croissante
   * @param profiles profils demandés, dans l'ordre ; liste vide : profil {@code default}
   * @param diagnostics collecteur des {@code CONFIG_PARSE_ERROR} et {@code CLOUD_CONFIG_NOT_FOUND}
   * @return fichiers appliqués, du moins au plus prioritaire ; liste vide si aucun
   */
  public List<Path> apply(Config config, List<String> names, List<String> profiles, Diagnostics diagnostics) {
    List<String> active = profiles.isEmpty() ? List.of("default") : profiles;
    List<Path> dirs = directories(names, active);
    List<String> bases = new ArrayList<>();
    bases.add("application");
    bases.addAll(names);
    for (String p : active) {
      bases.add("application-" + p);
      names.forEach(n -> bases.add(n + "-" + p));
    }
    ConfigLoader loader = new ConfigLoader(diagnostics, p -> PREFIX + relative(p));
    List<Path> applied = new ArrayList<>();
    boolean own = false;
    for (String base : bases) {
      for (Path dir : dirs) {
        for (String ext : EXTENSIONS) {
          Path f = dir.resolve(base + ext);
          if (!Files.isRegularFile(f)) {
            continue;
          }
          applied.add(f);
          own |= !base.equals("application") && !base.startsWith("application-");
          for (List<ConfigEntry> doc : loader.read(f)) {
            String cond = ConfigLoader.activation(doc);
            if (cond == null || ConfigLoader.matches(cond, active)) {
              doc.forEach(e -> config.put(decipher(e)));
            }
          }
        }
      }
    }
    if (!own) {
      diagnostics.info("CLOUD_CONFIG_NOT_FOUND", "aucun fichier " + String.join(", ", names)
          + "[-profil].yml|properties dans le dépôt Spring Cloud Config " + root.getFileName()
          + " (répertoires lus : " + String.join(", ", dirs.stream().map(this::label).toList()) + ")",
          null);
    }
    return applied;
  }

  /** Racine puis répertoires de recherche existants, sans doublon, dans l'ordre. */
  private List<Path> directories(List<String> names, List<String> profiles) {
    Set<Path> out = new LinkedHashSet<>();
    out.add(root);
    for (String pattern : searchPaths) {
      for (String expanded : expand(pattern, names, profiles)) {
        out.addAll(matching(expanded));
      }
    }
    return List.copyOf(out);
  }

  /** Motif décliné pour chaque nom d'application et chaque profil. */
  private static List<String> expand(String pattern, List<String> names, List<String> profiles) {
    return substitute(substitute(List.of(pattern.strip()), "{application}", names), "{profile}", profiles);
  }

  private static List<String> substitute(List<String> patterns, String key, List<String> values) {
    List<String> out = new ArrayList<>();
    for (String p : patterns) {
      if (p.contains(key)) {
        values.forEach(v -> out.add(p.replace(key, v)));
      } else {
        out.add(p);
      }
    }
    return out;
  }

  /** Répertoires du dépôt qui correspondent au motif, triés par chemin. */
  private List<Path> matching(String pattern) {
    String p = pattern.replace('\\', '/');
    while (p.startsWith("/")) {
      p = p.substring(1);
    }
    while (p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    if (p.isEmpty()) {
      return List.of(root);
    }
    if (!p.contains("*") && !p.contains("?")) {
      Path dir = root.resolve(p).normalize();
      return dir.startsWith(root) && Files.isDirectory(dir) ? List.of(dir) : List.of();
    }
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + p);
    try (Stream<Path> s = Files.walk(root, MAX_DEPTH)) {
      return s.filter(Files::isDirectory)
          .filter(d -> !d.equals(root) && !root.relativize(d).startsWith(".git"))
          .filter(d -> matcher.matches(Path.of(relative(d))))
          .sorted((a, b) -> relative(a).compareTo(relative(b)))
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  /** Valeur chiffrée côté serveur ({@code {cipher}…}) : illisible ici, donc masquée. */
  private static ConfigEntry decipher(ConfigEntry e) {
    String v = e.value();
    return v != null && v.strip().toLowerCase(Locale.ROOT).startsWith("{cipher}")
        ? new ConfigEntry(e.key(), Secrets.MASK, e.file(), e.line()) : e;
  }

  private String relative(Path p) {
    return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  private String label(Path dir) {
    String r = relative(dir);
    return r.isEmpty() ? "/" : r;
  }
}
