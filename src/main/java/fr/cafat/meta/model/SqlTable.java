package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Table lue ou écrite par une requête SQL. {@code access} vaut read ou write. */
public record SqlTable(
    @JsonProperty("schema") String schema,
    @JsonProperty("name") String name,
    @JsonProperty("access") String access) {

  /**
   * Clé de tri stable : schéma, nom, accès.
   *
   * @return clé composée, schéma null remplacé par une chaîne vide
   */
  public String sortKey() {
    return (schema == null ? "" : schema) + "\u0000" + name + "\u0000" + access;
  }
}
