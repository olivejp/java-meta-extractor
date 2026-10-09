package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Colonne persistante. {@code kind} vaut id, basic, version, join (clé étrangère d'une relation
 * propriétaire), pk_join (jointure JOINED vers le parent) ou discriminator.
 */
public record Column(
    @JsonProperty("name") String name,
    @JsonProperty("field") String field,
    @JsonProperty("java_type") String javaType,
    @JsonProperty("table") String table,
    @JsonProperty("kind") String kind,
    @JsonProperty("pk") boolean pk,
    @JsonProperty("nullable") Boolean nullable,
    @JsonProperty("unique") Boolean unique,
    @JsonProperty("length") Integer length,
    @JsonProperty("precision") Integer precision,
    @JsonProperty("scale") Integer scale,
    @JsonProperty("generated") String generated,
    @JsonProperty("enum_values") List<String> enumValues,
    @JsonProperty("enum_storage") String enumStorage,
    @JsonProperty("references") String references,
    @JsonProperty("inherited_from") String inheritedFrom) {

  /**
   * Copie avec une autre table.
   *
   * @param newTable table physique ; null : table principale du porteur
   * @return nouvelle colonne, autres champs inchangés
   */
  public Column withTable(String newTable) {
    return new Column(name, field, javaType, newTable, kind, pk, nullable, unique, length, precision,
        scale, generated, enumValues, enumStorage, references, inheritedFrom);
  }

  /**
   * Copie marquée comme héritée.
   *
   * @param from id de l'entité qui déclare la colonne
   * @return nouvelle colonne, autres champs inchangés
   */
  public Column withInheritedFrom(String from) {
    return new Column(name, field, javaType, table, kind, pk, nullable, unique, length, precision,
        scale, generated, enumValues, enumStorage, references, from);
  }

  /**
   * Copie avec un autre nom.
   *
   * @param newName nom physique de la colonne
   * @return nouvelle colonne, autres champs inchangés
   */
  public Column withName(String newName) {
    return new Column(newName, field, javaType, table, kind, pk, nullable, unique, length, precision,
        scale, generated, enumValues, enumStorage, references, inheritedFrom);
  }

  /**
   * Copie avec une autre référence de clé étrangère.
   *
   * @param ref id de l'entité référencée ; null si aucune
   * @return nouvelle colonne, autres champs inchangés
   */
  public Column withReferences(String ref) {
    return new Column(name, field, javaType, table, kind, pk, nullable, unique, length, precision,
        scale, generated, enumValues, enumStorage, ref, inheritedFrom);
  }

  /**
   * Clé de tri stable : table, nom, champ, kind.
   *
   * @return clé composée, champs null remplacés par une chaîne vide
   */
  public String sortKey() {
    return (table == null ? "" : table) + "\u0000" + (name == null ? "" : name) + "\u0000"
        + (field == null ? "" : field) + "\u0000" + kind;
  }
}
