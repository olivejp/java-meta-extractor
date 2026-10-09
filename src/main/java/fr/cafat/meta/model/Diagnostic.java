package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Élément que l'outil n'a pas su traiter. {@code level} vaut error, warning ou info. */
public record Diagnostic(
    @JsonProperty("id") String id,
    @JsonProperty("level") String level,
    @JsonProperty("code") String code,
    @JsonProperty("message") String message,
    @JsonProperty("source") Source source) {

  /**
   * Copie avec un autre id.
   *
   * @param newId id à poser ({@code app:CODE:<sha1 tronqué>}, éventuellement suffixé)
   * @return nouveau diagnostic, autres champs inchangés
   */
  public Diagnostic withId(String newId) {
    return new Diagnostic(newId, level, code, message, source);
  }
}
