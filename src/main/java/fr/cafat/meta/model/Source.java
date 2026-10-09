package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Provenance d'un élément : classe pleinement qualifiée, fichier relatif au dépôt, ligne (1-based). */
public record Source(
    @JsonProperty("class") String className,
    @JsonProperty("file") String file,
    @JsonProperty("line") Integer line) {

  /**
   * Provenance complète.
   *
   * @param className nom qualifié de la classe ; null accepté
   * @param file chemin relatif au dépôt ; null accepté
   * @param line ligne 1-based ; null accepté
   * @return nouvelle provenance
   */
  public static Source of(String className, String file, Integer line) {
    return new Source(className, file, line);
  }

  /**
   * Provenance d'un fichier non Java (XML, YAML…), sans classe.
   *
   * @param file chemin relatif au dépôt
   * @param line ligne 1-based ; null si inconnue
   * @return nouvelle provenance, classe à null
   */
  public static Source file(String file, Integer line) {
    return new Source(null, file, line);
  }

  /**
   * Clé de tri stable : fichier, ligne, classe.
   *
   * @return clé composée, ligne sur 9 chiffres, champs null remplacés par une chaîne vide ou 0
   */
  public String sortKey() {
    return (file == null ? "" : file) + "\u0000" + String.format("%09d", line == null ? 0 : line)
        + "\u0000" + (className == null ? "" : className);
  }
}
