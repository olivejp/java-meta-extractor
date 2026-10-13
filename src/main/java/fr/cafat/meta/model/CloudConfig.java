package fr.cafat.meta.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Passage de l'application sous Spring Cloud Config. {@code status} vaut {@code not_client}
 * (configuration locale seule), {@code not_provided} (cliente, lancée sans dépôt de configuration),
 * {@code not_found} (aucun fichier propre à l'application dans le dépôt) ou {@code applied}.
 *
 * @param client vrai si l'application est cliente de Spring Cloud Config
 * @param status état de la lecture de la configuration distante
 * @param names noms demandés au serveur ({@code spring.cloud.config.name}, sinon
 *     {@code spring.application.name}) ; vide si non cliente
 * @param label branche du dépôt de configuration lue ; null si non lue ou inconnue
 * @param files fichiers appliqués, relatifs au dépôt de configuration, du moins au plus prioritaire
 */
public record CloudConfig(
    @JsonProperty("client") boolean client,
    @JsonProperty("status") String status,
    @JsonProperty("names") List<String> names,
    @JsonProperty("label") String label,
    @JsonProperty("files") List<String> files) {

  public static final String NOT_CLIENT = "not_client";
  public static final String NOT_PROVIDED = "not_provided";
  public static final String NOT_FOUND = "not_found";
  public static final String APPLIED = "applied";

  /**
   * Application non cliente : configuration locale seule.
   *
   * @return état {@code not_client}, sans nom, label ni fichier
   */
  public static CloudConfig notClient() {
    return new CloudConfig(false, NOT_CLIENT, List.of(), null, List.of());
  }
}
