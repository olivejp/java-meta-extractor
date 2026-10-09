package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Accès SQL. {@code origin} dit d'où vient le texte (native_query, jdbc_template, mybatis_xml…) ;
 * {@code parsed} vaut false si les tables viennent du repli lexical.
 */
public record SqlAccess(
    @JsonProperty("id") String id,
    @JsonProperty("origin") String origin,
    @JsonProperty("datasource") String datasource,
    @JsonProperty("sql") String sql,
    @JsonProperty("tables") List<SqlTable> tables,
    @JsonProperty("parsed") boolean parsed,
    @JsonProperty("caller") String caller,
    @JsonProperty("source") Source source) {

  /**
   * Copie avec un autre id.
   *
   * @param newId id à poser (ex. id suffixé {@code ~2})
   * @return nouvel accès, autres champs inchangés
   */
  public SqlAccess withId(String newId) {
    return new SqlAccess(newId, origin, datasource, sql, tables, parsed, caller, source);
  }
}
