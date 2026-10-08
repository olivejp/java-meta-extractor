package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Discriminator(
    @JsonProperty("column") String column,
    @JsonProperty("type") String type,
    @JsonProperty("value") String value) {
}
