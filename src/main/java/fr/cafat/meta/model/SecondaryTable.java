package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Table secondaire d'une entité ({@code @SecondaryTable}) et ses colonnes de jointure. */
public record SecondaryTable(
    @JsonProperty("schema") String schema,
    @JsonProperty("name") String name,
    @JsonProperty("join_columns") List<String> joinColumns) {
}
