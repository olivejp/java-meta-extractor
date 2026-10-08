package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Messaging(
    @JsonProperty("id") String id,
    @JsonProperty("role") String role,
    @JsonProperty("destination_type") String destinationType,
    @JsonProperty("raw_destination") String rawDestination,
    @JsonProperty("destination") String destination,
    @JsonProperty("caller") String caller,
    @JsonProperty("source") Source source) {

  public Messaging withId(String newId) {
    return new Messaging(newId, role, destinationType, rawDestination, destination, caller, source);
  }
}
