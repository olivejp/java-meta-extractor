package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Source de données déclarée dans la configuration. Aucun identifiant ni mot de passe n'est recopié :
 * l'URL JDBC est nettoyée avant d'arriver ici.
 */
public record Datasource(
    @JsonProperty("id") String id,
    @JsonProperty("kind") String kind,
    @JsonProperty("jdbc_url") String jdbcUrl,
    @JsonProperty("jndi_name") String jndiName,
    @JsonProperty("default_schema") String defaultSchema,
    @JsonProperty("config_prefix") String configPrefix,
    @JsonProperty("source") Source source) {
}
