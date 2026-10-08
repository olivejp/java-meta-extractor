package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Classe persistante : entity, embeddable ou mapped_superclass. */
public record Entity(
    @JsonProperty("id") String id,
    @JsonProperty("class") String className,
    @JsonProperty("name") String name,
    @JsonProperty("kind") String kind,
    @JsonProperty("datasource") String datasource,
    @JsonProperty("schema") String schema,
    @JsonProperty("table") String table,
    @JsonProperty("is_view") Boolean isView,
    @JsonProperty("parent") String parent,
    @JsonProperty("inheritance") Inheritance inheritance,
    @JsonProperty("secondary_tables") List<SecondaryTable> secondaryTables,
    @JsonProperty("source") Source source,
    @JsonProperty("columns") List<Column> columns) {
}
