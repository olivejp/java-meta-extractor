package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Échange JMS. {@code role} vaut produce ou consume ; {@code raw_destination} est la valeur du
 * code, {@code destination} sa valeur résolue.
 */
public record Messaging(
    @JsonProperty("id") String id,
    @JsonProperty("role") String role,
    @JsonProperty("destination_type") String destinationType,
    @JsonProperty("raw_destination") String rawDestination,
    @JsonProperty("destination") String destination,
    @JsonProperty("caller") String caller,
    @JsonProperty("source") Source source) {

  /**
   * Copie avec un autre id.
   *
   * @param newId id à poser (ex. id suffixé {@code ~2})
   * @return nouvel échange, autres champs inchangés
   */
  public Messaging withId(String newId) {
    return new Messaging(newId, role, destinationType, rawDestination, destination, caller, source);
  }
}
