package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Association JPA entre deux classes persistantes ({@code type} : ONE_TO_ONE, MANY_TO_ONE…). */
public record Relation(
  /**
   * Copie avec une autre table de jointure.
   *
   * @param table table de jointure ; null si aucune
   * @return nouvelle relation, autres champs inchangés
   */
    @JsonProperty("id") String id,
    @JsonProperty("from_entity") String fromEntity,
    @JsonProperty("to_entity") String toEntity,
    @JsonProperty("to_class") String toClass,
    @JsonProperty("field") String field,
    @JsonProperty("type") String type,
    @JsonProperty("owning_side") boolean owningSide,
    @JsonProperty("mapped_by") String mappedBy,
    @JsonProperty("join_columns") List<String> joinColumns,
    @JsonProperty("inverse_join_columns") List<String> inverseJoinColumns,
    @JsonProperty("join_table") TableRef joinTable,
    @JsonProperty("optional") Boolean optional,
    @JsonProperty("inherited_from") String inheritedFrom,
    @JsonProperty("source") Source source) {
  public Relation withJoinTable(TableRef table) {
    return new Relation(id, fromEntity, toEntity, toClass, field, type, owningSide, mappedBy, joinColumns,
        inverseJoinColumns, table, optional, inheritedFrom, source);
  }
}
