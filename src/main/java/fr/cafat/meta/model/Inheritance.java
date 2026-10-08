package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Héritage JPA d'une entité appartenant à une hiérarchie. {@code root} est l'id de l'entité racine,
 * {@code join_columns} les colonnes de jointure vers le parent (JOINED uniquement).
 */
public record Inheritance(
    @JsonProperty("strategy") String strategy,
    @JsonProperty("root") String root,
    @JsonProperty("discriminator") Discriminator discriminator,
    @JsonProperty("join_columns") List<String> joinColumns) {
}
