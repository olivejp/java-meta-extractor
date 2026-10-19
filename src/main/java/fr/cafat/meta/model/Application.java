package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Application déployable analysée : identité, description, version, technologies, dépôt, commit, profils
 * appliqués, environnement ciblé, passage sous Spring Cloud Config et sources de données.
 */
public record Application(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("version") String version,
    @JsonProperty("tech") List<String> tech,
    @JsonProperty("repository") String repository,
    @JsonProperty("commit") String commit,
    @JsonProperty("module") String module,
    @JsonProperty("profiles") List<String> profiles,
    @JsonProperty("context_path") String contextPath,
    @JsonProperty("environment") String environment,
    @JsonProperty("cloud_config") CloudConfig cloudConfig,
    @JsonProperty("datasources") List<Datasource> datasources) {
}
