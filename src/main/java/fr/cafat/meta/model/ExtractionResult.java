package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Racine du contrat JSON : un fichier par application. */
public record ExtractionResult(
    @JsonProperty("contract_version") String contractVersion,
    @JsonProperty("application") Application application,
    @JsonProperty("entities") List<Entity> entities,
    @JsonProperty("relations") List<Relation> relations,
    @JsonProperty("sql_accesses") List<SqlAccess> sqlAccesses,
    @JsonProperty("endpoints") List<Endpoint> endpoints,
    @JsonProperty("calls") List<Call> calls,
    @JsonProperty("messaging") List<Messaging> messaging,
    @JsonProperty("diagnostics") List<Diagnostic> diagnostics) {

  public static final String CONTRACT_VERSION = "1.1";
}
