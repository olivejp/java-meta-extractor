package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Appel HTTP sortant. {@code path} est le chemin relatif à la base de l'URL quand il est connu.
 */
public record Call(
    @JsonProperty("id") String id,
    @JsonProperty("client") String client,
    @JsonProperty("method") String method,
    @JsonProperty("raw_url") String rawUrl,
    @JsonProperty("resolved_url") String resolvedUrl,
    @JsonProperty("path") String path,
    @JsonProperty("target_app") String targetApp,
    @JsonProperty("caller") String caller,
    @JsonProperty("source") Source source) {

  /**
   * Copie avec un autre id.
   *
   * @param newId id à poser (ex. id suffixé {@code ~2})
   * @return nouvel appel, autres champs inchangés
   */
  public Call withId(String newId) {
    return new Call(newId, client, method, rawUrl, resolvedUrl, path, targetApp, caller, source);
  }
}
