package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Provenance d'un élément : classe pleinement qualifiée, fichier relatif au dépôt, ligne (1-based). */
public record Source(
    @JsonProperty("class") String className,
    @JsonProperty("file") String file,
    @JsonProperty("line") Integer line) {

  public static Source of(String className, String file, Integer line) {
    return new Source(className, file, line);
  }

  public static Source file(String file, Integer line) {
    return new Source(null, file, line);
  }

  /** Clé de tri stable : fichier, ligne, classe. */
  public String sortKey() {
    return (file == null ? "" : file) + "\u0000" + String.format("%09d", line == null ? 0 : line)
        + "\u0000" + (className == null ? "" : className);
  }
}
