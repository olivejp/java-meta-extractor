package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SqlAccess(
    @JsonProperty("id") String id,
    @JsonProperty("origin") String origin,
    @JsonProperty("datasource") String datasource,
    @JsonProperty("sql") String sql,
    @JsonProperty("tables") List<SqlTable> tables,
    @JsonProperty("parsed") boolean parsed,
    @JsonProperty("caller") String caller,
    @JsonProperty("source") Source source) {

  public SqlAccess withId(String newId) {
    return new SqlAccess(newId, origin, datasource, sql, tables, parsed, caller, source);
  }
}
