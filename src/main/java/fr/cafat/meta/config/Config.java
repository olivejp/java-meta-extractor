package fr.cafat.meta.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Configuration effective d'une application (fichiers de base puis surcharges de profils).
 * Recherche exacte puis « relâchée » à la Spring ({@code baseUrl}, {@code base-url} et
 * {@code base_url} sont équivalents). Les espaces réservés {@code ${cle:defaut}} sont résolus
 * récursivement ; les clés sensibles sont toujours rendues {@code ***}.
 */
public final class Config {

  private static final int MAX_DEPTH = 10;

  private final Map<String, ConfigEntry> entries = new TreeMap<>();
  private final Map<String, String> relaxed = new TreeMap<>();

  /** Résultat d'une résolution : texte (espaces réservés inconnus conservés) et clés manquantes. */
  public record Resolution(String value, Set<String> missing) {

    /**
     * Vrai si toutes les clés citées sont résolues.
     *
     * @return true si {@code missing} est vide
     */
    public boolean complete() {
      return missing.isEmpty();
    }
  }

  /**
   * Configuration sans entrée.
   *
   * @return nouvelle configuration vide
   */
  public static Config empty() {
    return new Config();
  }

  /**
   * Ajoute ou surcharge une entrée (la dernière ajoutée gagne).
   *
   * @param e entrée à ajouter, obligatoire ; remplace l'entrée de même clé exacte
   */
  public void put(ConfigEntry e) {
    entries.put(e.key(), e);
    relaxed.put(normalize(e.key()), e.key());
  }

  /**
   * Vrai si aucune entrée.
   *
   * @return true si la configuration est vide
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * Entrée de la clé : recherche exacte, puis relâchée.
   *
   * @param key clé de configuration (ex. {@code spring.datasource.url}), obligatoire
   * @return entrée trouvée, valeur brute non résolue ; null si absente
   */
  public ConfigEntry entry(String key) {
    ConfigEntry e = entries.get(key);
    if (e != null) {
      return e;
    }
    String k = relaxed.get(normalize(key));
    return k == null ? null : entries.get(k);
  }

  /**
   * Valeur brute (non résolue).
   *
   * @param key clé de configuration, recherche exacte puis relâchée
   * @return valeur telle qu'écrite (espaces réservés gardés, secrets non masqués) ; null si clé absente
   */
  public String raw(String key) {
    ConfigEntry e = entry(key);
    return e == null ? null : e.value();
  }

  /**
   * Valeur résolue, ou null si la clé est absente ou si la résolution est incomplète.
   *
   * @param key clé de configuration, recherche exacte puis relâchée
   * @return valeur résolue ; {@code ***} si clé sensible ; null si clé absente ou espace réservé inconnu
   */
  public String get(String key) {
    ConfigEntry e = entry(key);
    if (e == null) {
      return null;
    }
    if (Secrets.isSensitiveKey(e.key())) {
      return Secrets.MASK;
    }
    Resolution r = resolve(e.value());
    return r.complete() ? r.value() : null;
  }

  /**
   * Première valeur résolue parmi des clés alternatives.
   *
   * @param keys clés alternatives, par ordre de priorité
   * @return première valeur résolue non vide (voir {@link #get}) ; null si aucune
   */
  public String first(String... keys) {
    for (String k : keys) {
      String v = get(k);
      if (v != null && !v.isEmpty()) {
        return v;
      }
    }
    return null;
  }

  /**
   * Première entrée présente parmi des clés alternatives.
   *
   * @param keys clés alternatives, par ordre de priorité
   * @return première entrée trouvée, valeur non résolue ; null si aucune
   */
  public ConfigEntry firstEntry(String... keys) {
    for (String k : keys) {
      ConfigEntry e = entry(k);
      if (e != null) {
        return e;
      }
    }
    return null;
  }

  /**
   * Toutes les entrées, triées par clé.
   *
   * @return copie des entrées, valeurs non résolues
   */
  public List<ConfigEntry> entries() {
    return new ArrayList<>(entries.values());
  }

  /**
   * Résout les espaces réservés {@code ${cle:defaut}} du texte ; les expressions {@code #{…}} restent
   * inconnues.
   *
   * @param text texte à résoudre ; null accepté
   * @return texte résolu (null si {@code text} null), espaces réservés inconnus gardés tels quels, clés
   *     sensibles en {@code ***} ; clés manquantes et expressions {@code #{…}} dans {@code missing}
   */
  public Resolution resolve(String text) {
    Set<String> missing = new LinkedHashSet<>();
    String v = resolve(text, missing, 0);
    return new Resolution(v, missing);
  }

  private String resolve(String text, Set<String> missing, int depth) {
    if (text == null) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      int start = text.indexOf("${", i);
      int spel = text.indexOf("#{", i);
      if (spel >= 0 && (start < 0 || spel < start)) {
        int end = matching(text, spel + 1);
        if (end < 0) {
          out.append(text.substring(i));
          break;
        }
        out.append(text, i, end + 1);
        missing.add(text.substring(spel, end + 1));
        i = end + 1;
        continue;
      }
      if (start < 0) {
        out.append(text.substring(i));
        break;
      }
      int end = matching(text, start + 1);
      if (end < 0) {
        out.append(text.substring(i));
        break;
      }
      out.append(text, i, start);
      String inner = text.substring(start + 2, end);
      int colon = topLevelColon(inner);
      String key = colon < 0 ? inner : inner.substring(0, colon);
      String def = colon < 0 ? null : inner.substring(colon + 1);
      key = resolve(key, missing, depth + 1);
      ConfigEntry e = depth > MAX_DEPTH ? null : entry(key);
      if (e != null && Secrets.isSensitiveKey(e.key())) {
        out.append(Secrets.MASK);
      } else if (e != null) {
        out.append(resolve(e.value(), missing, depth + 1));
      } else if (def != null) {
        out.append(Secrets.isSensitiveKey(key) ? Secrets.MASK : resolve(def, missing, depth + 1));
      } else {
        missing.add(key);
        out.append("${").append(key).append('}');
      }
      i = end + 1;
    }
    return out.toString();
  }

  /** Index de l'accolade fermante correspondant à celle en {@code open}. */
  private static int matching(String s, int open) {
    int level = 0;
    for (int k = open; k < s.length(); k++) {
      char c = s.charAt(k);
      if (c == '{') {
        level++;
      } else if (c == '}') {
        level--;
        if (level == 0) {
          return k;
        }
      }
    }
    return -1;
  }

  private static int topLevelColon(String s) {
    int level = 0;
    for (int k = 0; k < s.length(); k++) {
      char c = s.charAt(k);
      if (c == '{') {
        level++;
      } else if (c == '}') {
        level--;
      } else if (c == ':' && level == 0) {
        return k;
      }
    }
    return -1;
  }

  /**
   * Forme canonique relâchée : minuscules, sans tirets ni soulignés.
   *
   * @param key clé de configuration, obligatoire
   * @return clé normalisée (ex. {@code base-url} → {@code baseurl})
   */
  public static String normalize(String key) {
    return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
  }
}
