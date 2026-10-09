package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Endpoint HTTP exposé. {@code framework} vaut spring_mvc ou jax_rs ;
 * {@code handler} = {@code Classe#méthode}.
 */
public record Endpoint(
    @JsonProperty("id") String id,
    @JsonProperty("framework") String framework,
    @JsonProperty("method") String method,
    @JsonProperty("path") String path,
    @JsonProperty("handler") String handler,
    @JsonProperty("request_type") String requestType,
    @JsonProperty("response_type") String responseType,
    @JsonProperty("source") Source source) {

  /**
   * Copie avec un autre id.
   *
   * @param newId id à poser (ex. id suffixé {@code ~2})
   * @return nouvel endpoint, autres champs inchangés
   */
  public Endpoint withId(String newId) {
    return new Endpoint(newId, framework, method, path, handler, requestType, responseType, source);
  }
}
