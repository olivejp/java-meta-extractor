package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Colonne discriminante d'une hiérarchie. {@code type} vaut STRING, CHAR ou INTEGER. */
public record Discriminator(
    @JsonProperty("column") String column,
    @JsonProperty("type") String type,
    @JsonProperty("value") String value) {
}
