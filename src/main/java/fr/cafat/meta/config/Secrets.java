package fr.cafat.meta.config;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Repérage et masquage des valeurs sensibles (mots de passe, identifiants, jetons). */
public final class Secrets {

  public static final String MASK = "***";

  private static final Set<String> EXACT = Set.of("user", "username", "login", "key", "pass",
      "userid", "uid", "pw");
  private static final String[] CONTAINS = {"password", "passwd", "pwd", "secret", "token",
      "credential", "apikey", "accesskey", "privatekey", "passphrase"};
  private static final Pattern URL_PARAM = Pattern.compile(
      "(?i)([;&?:,]\\s*)(user|username|user\\.name|login|password|passwd|pwd|pass|secret|token|"
          + "access_token|api[-_]?key|apikey|key|credentials?|client[-_]?secret)(\\s*=\\s*)([^;&#,\\s]*)");
  private static final Pattern USER_INFO = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.:-]*://)([^/@?#]*)@");
  /** Forme Oracle sans « :// » : {@code jdbc:oracle:thin:u/p@hôte}. */
  private static final Pattern ORACLE_CREDENTIALS = Pattern.compile("(?i)^(jdbc:oracle:[a-z0-9]+:)[^@/:]+/[^@]*@");

  private Secrets() {
  }

  /**
   * Vrai si le dernier segment de la clé désigne un secret ou un identifiant.
   *
   * @param key clé de configuration ou nom de propriété ; null accepté
   * @return true si le dernier segment (insensible à la casse, tirets et soulignés ignorés) vaut
   *     {@code user}, {@code login}… ou contient {@code password}, {@code token}… ; false si null
   */
  public static boolean isSensitiveKey(String key) {
    if (key == null) {
      return false;
    }
    String last = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
        .replace("-", "").replace("_", "");
    int bracket = last.indexOf('[');
    if (bracket >= 0) {
      last = last.substring(0, bracket);
    }
    if (EXACT.contains(last)) {
      return true;
    }
    for (String c : CONTAINS) {
      if (last.contains(c)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Retire l'userinfo ({@code //u:p@h} → {@code //h}) et masque les paramètres sensibles
   * ({@code password=x} → {@code password=***}).
   *
   * @param url URL JDBC ou HTTP ; null accepté
   * @return URL nettoyée ; null si {@code url} null
   */
  public static String sanitizeUrl(String url) {
    if (url == null) {
      return null;
    }
    String u = ORACLE_CREDENTIALS.matcher(url).replaceFirst("$1@");
    // jdbc:postgresql://u:p@h → la partie après « jdbc: » porte le schéma réel
    java.util.regex.Matcher m = USER_INFO.matcher(u);
    if (m.find()) {
      u = m.group(1) + u.substring(m.end());
    } else {
      int idx = u.indexOf("://");
      int at = u.indexOf('@');
      int slash = idx < 0 ? -1 : u.indexOf('/', idx + 3);
      if (idx >= 0 && at > idx && (slash < 0 || at < slash)) {
        u = u.substring(0, idx + 3) + u.substring(at + 1);
      }
    }
    return URL_PARAM.matcher(u).replaceAll("$1$2$3" + MASK);
  }
}
