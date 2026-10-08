package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Référence à une table physique (schéma éventuel + nom). */
public record TableRef(
    @JsonProperty("schema") String schema,
    @JsonProperty("name") String name) {
}
