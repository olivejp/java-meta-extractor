package fr.cafat.meta.config;

import fr.cafat.meta.model.Datasource;
import fr.cafat.meta.model.Source;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détection des sources de données : clés {@code <prefixe>.url|jdbc-url} portant une URL JDBC,
 * {@code <prefixe>.jndi-name}, et unités de persistance. Les identifiants et mots de passe ne sont
 * jamais lus ; les URL sont nettoyées ({@link Secrets#sanitizeUrl}).
 */
public final class DatasourceDetector {

  private static final Set<String> POOL_SEGMENTS = Set.of("hikari", "tomcat", "dbcp2", "oracleucp",
      "configuration", "xa");
  private static final Set<String> GENERIC_SEGMENTS = Set.of("datasource", "datasources", "db", "jdbc",
      "database");
  private static final List<String> EXCLUDED_PREFIXES = List.of("spring.flyway", "spring.liquibase",
      "spring.r2dbc", "flyway", "liquibase", "management", "spring.batch", "spring.quartz",
      "spring.session", "spring.integration");
  private static final Pattern CURRENT_SCHEMA = Pattern.compile("(?i)[?&;:]currentSchema=([^;&]+)");
  private static final Pattern AS400_LIBRARIES = Pattern.compile("(?i)[;:]libraries=([^;&]+)");

  /** Source de données détectée et ce qui permet d'y rattacher des entités. */
  public record Detected(Datasource datasource, String configPrefix, PersistenceUnit unit) {
  }

  private DatasourceDetector() {
  }

  public static List<Detected> detect(Config config, List<PersistenceUnit> units) {
    Map<String, ConfigEntry> urlByPrefix = new LinkedHashMap<>();
    Map<String, ConfigEntry> jndiByPrefix = new LinkedHashMap<>();
    for (ConfigEntry e : config.entries()) {
      String key = e.key();
      int dot = key.lastIndexOf('.');
      if (dot < 0) {
        continue;
      }
      String last = Config.normalize(key.substring(dot + 1));
      String prefix = stripPool(key.substring(0, dot));
      if (isExcluded(prefix)) {
        continue;
      }
      if (last.equals("url") || last.equals("jdbcurl")) {
        String value = config.resolve(e.value()).value();
        if (value != null && value.strip().toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
          urlByPrefix.putIfAbsent(prefix, e);
        } else if (value != null && value.contains("${") && prefix.toLowerCase(Locale.ROOT).contains("datasource")) {
          urlByPrefix.putIfAbsent(prefix, e);
        }
      } else if (last.equals("jndiname") && prefix.toLowerCase(Locale.ROOT).contains("datasource")) {
        jndiByPrefix.putIfAbsent(prefix, e);
      }
    }
    List<Detected> out = new ArrayList<>();
    Map<String, Integer> idCount = new LinkedHashMap<>();
    List<String> prefixes = new ArrayList<>(urlByPrefix.keySet());
    for (String p : jndiByPrefix.keySet()) {
      if (!prefixes.contains(p)) {
        prefixes.add(p);
      }
    }
    prefixes.sort(String::compareTo);
    List<String> ids = new ArrayList<>();
    for (String p : prefixes) {
      String id = idFor(p);
      ids.add(id);
      idCount.merge(id, 1, Integer::sum);
    }
    for (int i = 0; i < prefixes.size(); i++) {
      String prefix = prefixes.get(i);
      String id = idCount.get(ids.get(i)) > 1 ? prefix : ids.get(i);
      ConfigEntry urlEntry = urlByPrefix.get(prefix);
      ConfigEntry jndiEntry = jndiByPrefix.get(prefix);
      String url = urlEntry == null ? null : Secrets.sanitizeUrl(config.resolve(urlEntry.value()).value());
      String jndi = jndiEntry == null ? null : config.resolve(jndiEntry.value()).value();
      String schema = config.first(prefix + ".schema", prefix + ".default-schema",
          prefix + ".hikari.schema", prefix + ".jpa.properties.hibernate.default_schema");
      if (schema == null && prefix.equals("spring.datasource")) {
        schema = config.first("spring.jpa.properties.hibernate.default_schema");
      }
      if (schema == null) {
        schema = schemaFromUrl(url);
      }
      ConfigEntry src = urlEntry != null ? urlEntry : jndiEntry;
      out.add(new Detected(new Datasource(id, kindOf(url, null), url, jndi, schema, prefix,
          new Source(null, src.file(), src.line())), prefix, null));
    }
    for (PersistenceUnit pu : units) {
      String url = pu.property("jakarta.persistence.jdbc.url", "javax.persistence.jdbc.url",
          "hibernate.connection.url");
      url = url == null ? null : Secrets.sanitizeUrl(config.resolve(url).value());
      String jndi = pu.jtaDataSource() != null ? pu.jtaDataSource() : pu.nonJtaDataSource();
      String schema = pu.property("hibernate.default_schema", "openjpa.jdbc.Schema",
          "eclipselink.target-database.schema");
      if (schema == null) {
        schema = schemaFromUrl(url);
      }
      String id = pu.name().isEmpty() ? "default" : pu.name();
      out.add(new Detected(new Datasource(id, kindOf(url, pu.property("hibernate.dialect")), url, jndi,
          schema, null, new Source(null, pu.file(), pu.line())), null, pu));
    }
    return out;
  }

  private static String stripPool(String prefix) {
    String p = prefix;
    while (true) {
      int dot = p.lastIndexOf('.');
      if (dot < 0 || !POOL_SEGMENTS.contains(Config.normalize(p.substring(dot + 1)))) {
        return p;
      }
      p = p.substring(0, dot);
    }
  }

  private static boolean isExcluded(String prefix) {
    for (String ex : EXCLUDED_PREFIXES) {
      if (prefix.equals(ex) || prefix.startsWith(ex + ".")) {
        return true;
      }
    }
    return false;
  }

  /** Dernier segment significatif du préfixe ; {@code spring.datasource} → {@code default}. */
  static String idFor(String prefix) {
    if (prefix.equals("spring.datasource")) {
      return "default";
    }
    String[] segs = prefix.split("\\.");
    for (int i = segs.length - 1; i >= 0; i--) {
      if (!GENERIC_SEGMENTS.contains(Config.normalize(segs[i]))) {
        return segs[i];
      }
    }
    return prefix;
  }

  /** postgresql, db2 (jdbc:db2, jdbc:as400), other ; null si rien ne permet de le dire. */
  public static String kindOf(String url, String dialect) {
    if (url != null) {
      String u = url.toLowerCase(Locale.ROOT);
      if (u.startsWith("jdbc:postgresql:") || u.startsWith("jdbc:pgsql:")) {
        return "postgresql";
      }
      if (u.startsWith("jdbc:db2:") || u.startsWith("jdbc:as400:") || u.startsWith("jdbc:db2j:")) {
        return "db2";
      }
      if (u.startsWith("jdbc:")) {
        return "other";
      }
    }
    if (dialect != null) {
      String d = dialect.toLowerCase(Locale.ROOT);
      if (d.contains("postgres")) {
        return "postgresql";
      }
      if (d.contains("db2")) {
        return "db2";
      }
      return "other";
    }
    return null;
  }

  static String schemaFromUrl(String url) {
    if (url == null) {
      return null;
    }
    Matcher m = CURRENT_SCHEMA.matcher(url);
    if (m.find()) {
      String first = m.group(1).split(",")[0].strip();
      return first.isEmpty() ? null : first;
    }
    m = AS400_LIBRARIES.matcher(url);
    if (m.find()) {
      String first = m.group(1).split("[ ,]")[0].strip();
      return first.isEmpty() || first.startsWith("*") ? null : first;
    }
    return null;
  }
}
