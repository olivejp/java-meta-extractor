package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Application déployable analysée : identité, version, technologies, dépôt, commit, profils et
 * sources de données.
 */
public record Application(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("version") String version,
    @JsonProperty("tech") List<String> tech,
    @JsonProperty("repository") String repository,
    @JsonProperty("commit") String commit,
    @JsonProperty("module") String module,
    @JsonProperty("profiles") List<String> profiles,
    @JsonProperty("context_path") String contextPath,
    @JsonProperty("datasources") List<Datasource> datasources) {
}
