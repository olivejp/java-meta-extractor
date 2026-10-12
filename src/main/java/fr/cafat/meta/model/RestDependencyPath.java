package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Chemin déclaré d'une dépendance REST ({@code path.<cle>}). {@code template} est la valeur de la
 * configuration ({@code %s}, {@code {var}} gardés) ; {@code path} le chemin complet comparable à
 * celui des appels et des endpoints ({@code base_path} + gabarit, variables normalisées).
 */
public record RestDependencyPath(
    @JsonProperty("name") String name,
    @JsonProperty("template") String template,
    @JsonProperty("path") String path,
    @JsonProperty("called") boolean called,
    @JsonProperty("source") Source source) {
}
