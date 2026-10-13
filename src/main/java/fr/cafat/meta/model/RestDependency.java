package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Dépendance REST déclarée dans la configuration, convention CAFAT
 * {@code nc.cafat.rest-dependency.<nom>} ou {@code cafat.config.rest-dependency.<nom>} (Spring Cloud
 * Config), préfixe dans {@code config_prefix} : {@code service}, {@code version}, {@code type} et
 * chemins {@code path.<cle>}. {@code base_path} vaut {@code service + version} ; {@code called}
 * indique si un appel du code cite la dépendance.
 */
public record RestDependency(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("config_prefix") String configPrefix,
    @JsonProperty("target_app") String targetApp,
    @JsonProperty("type") String type,
    @JsonProperty("service") String service,
    @JsonProperty("version") String version,
    @JsonProperty("base_path") String basePath,
    @JsonProperty("host") String host,
    @JsonProperty("called") boolean called,
    @JsonProperty("paths") List<RestDependencyPath> paths,
    @JsonProperty("source") Source source) {
}
