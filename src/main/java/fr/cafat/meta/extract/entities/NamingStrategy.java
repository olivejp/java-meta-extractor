package fr.cafat.meta.extract.entities;

import fr.cafat.meta.config.Config;
import fr.cafat.meta.config.PersistenceUnit;
import fr.cafat.meta.extract.Diagnostics;
import java.util.List;
import java.util.Locale;

/**
 * Noms physiques à la manière d'Hibernate. Spring Boot : stratégie implicite
 * {@code SpringImplicitNamingStrategy} puis physique {@code CamelCaseToUnderscoresNamingStrategy}
 * (appliquée aussi aux noms explicites, comme le fait Hibernate). Hors Spring Boot : noms JPA
 * implicites, physique identité. Stratégie personnalisée : noms explicites tels qu'écrits, noms
 * implicites inconnus (null).
 */
public final class NamingStrategy {

  /** Stratégie physique : snake_case (Spring Boot), identité (JPA), inconnue (classe personnalisée). */
  public enum Physical { SNAKE_CASE, IDENTITY, UNKNOWN }

  private final Physical physical;
  private final boolean springImplicit;

  /**
   * Stratégie de nommage explicite.
   *
   * @param physical stratégie physique appliquée aux noms
   * @param springImplicit true : noms implicites à la Spring ({@code SpringImplicitNamingStrategy}) ;
   *     false : noms implicites JPA
   */
  public NamingStrategy(Physical physical, boolean springImplicit) {
    this.physical = physical;
    this.springImplicit = springImplicit;
  }

  /**
   * Stratégie par défaut de Spring Boot.
   *
   * @return snake_case, noms implicites à la Spring
   */
  public static NamingStrategy springBoot() {
    return new NamingStrategy(Physical.SNAKE_CASE, true);
  }

  /**
   * Stratégie par défaut de JPA hors Spring Boot.
   *
   * @return identité, noms implicites JPA
   */
  public static NamingStrategy jpa() {
    return new NamingStrategy(Physical.IDENTITY, false);
  }

  /**
   * Stratégie physique appliquée.
   *
   * @return stratégie physique
   */
  public Physical physical() {
    return physical;
  }

  /**
   * Stratégie effective d'après la configuration Spring et les unités de persistance.
   *
   * @param config configuration effective ({@code spring.jpa.hibernate.naming.*}), prioritaire
   * @param units unités de persistance ({@code hibernate.physical_naming_strategy}), en repli
   * @param springBoot true si l'application est Spring Boot : défauts Spring Boot
   * @param diagnostics collecteur du {@code NAMING_STRATEGY_UNKNOWN}
   * @return stratégie reconnue ; {@link Physical#UNKNOWN} si la classe déclarée est personnalisée
   */
  public static NamingStrategy detect(Config config, List<PersistenceUnit> units, boolean springBoot,
      Diagnostics diagnostics) {
    String declared = config.first("spring.jpa.hibernate.naming.physical-strategy",
        "spring.jpa.properties.hibernate.physical_naming_strategy", "spring.jpa.hibernate.naming-strategy",
        "spring.jpa.hibernate.naming_strategy");
    if (declared == null) {
      for (PersistenceUnit pu : units) {
        String v = pu.property("hibernate.physical_naming_strategy", "hibernate.ejb.naming_strategy");
        if (v != null) {
          declared = v;
          break;
        }
      }
    }
    String implicit = config.first("spring.jpa.hibernate.naming.implicit-strategy",
        "spring.jpa.properties.hibernate.implicit_naming_strategy");
    boolean springImplicit = springBoot && (implicit == null || implicit.endsWith("SpringImplicitNamingStrategy"));
    if (declared == null) {
      return new NamingStrategy(springBoot ? Physical.SNAKE_CASE : Physical.IDENTITY, springImplicit);
    }
    String simple = declared.substring(declared.lastIndexOf('.') + 1);
    switch (simple) {
      case "CamelCaseToUnderscoresNamingStrategy", "SpringPhysicalNamingStrategy", "ImprovedNamingStrategy" -> {
        return new NamingStrategy(Physical.SNAKE_CASE, springImplicit);
      }
      case "PhysicalNamingStrategyStandardImpl", "EJB3NamingStrategy", "DefaultNamingStrategy" -> {
        return new NamingStrategy(Physical.IDENTITY, springImplicit);
      }
      default -> {
        diagnostics.warning("NAMING_STRATEGY_UNKNOWN", "stratégie de nommage personnalisée " + declared
            + " : noms explicites recopiés tels qu'écrits, noms implicites inconnus", null);
        return new NamingStrategy(Physical.UNKNOWN, springImplicit);
      }
    }
  }

  /**
   * Nom physique d'un identifiant explicite (table, colonne, schéma).
   *
   * @param name nom écrit dans l'annotation ; null ou vide accepté
   * @return nom sans guillemets ni accents graves s'il en a, sinon converti par la stratégie
   *     physique ; null si {@code name} null ou vide
   */
  public String explicit(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String n = name.strip();
    if (isQuoted(n)) {
      return n.substring(1, n.length() - 1);
    }
    return physical == Physical.SNAKE_CASE ? snake(n) : n;
  }

  /**
   * Nom physique d'un identifiant implicite (déduit d'un nom Java).
   *
   * @param logical nom logique (nom d'entité, d'attribut…) ; null accepté
   * @return nom converti ; null si {@code logical} null ou stratégie inconnue
   */
  public String implicit(String logical) {
    if (logical == null || physical == Physical.UNKNOWN) {
      return null;
    }
    return physical == Physical.SNAKE_CASE ? snake(logical) : logical;
  }

  /**
   * Table implicite d'une entité : son nom d'entité.
   *
   * @param entityName nom d'entité JPA
   * @return nom physique ; null si stratégie inconnue
   */
  public String table(String entityName) {
    return implicit(entityName);
  }

  /**
   * Colonne de jointure implicite : attribut + "_" + colonne référencée.
   *
   * @param attribute nom de l'attribut d'association
   * @param referencedColumn colonne référencée de la cible, guillemets retirés ; null accepté
   * @return nom physique ; null si {@code referencedColumn} null ou stratégie inconnue
   */
  public String joinColumn(String attribute, String referencedColumn) {
    if (referencedColumn == null) {
      return null;
    }
    return implicit(attribute + "_" + unquote(referencedColumn));
  }

  /**
   * Table de jointure implicite. Spring : table propriétaire + "_" + attribut ; JPA : table
   * propriétaire + "_" + table cible.
   *
   * @param ownerTable table de l'entité propriétaire ; null accepté
   * @param attribute nom de l'attribut d'association (règle Spring)
   * @param targetTable table de l'entité cible (règle JPA) ; null accepté
   * @return nom physique ; null si une table nécessaire est absente ou stratégie inconnue
   */
  public String joinTable(String ownerTable, String attribute, String targetTable) {
    if (ownerTable == null) {
      return null;
    }
    if (springImplicit) {
      return implicit(unquote(ownerTable) + "_" + attribute);
    }
    return targetTable == null ? null : implicit(unquote(ownerTable) + "_" + unquote(targetTable));
  }

  /**
   * Table de collection implicite : nom d'entité + "_" + attribut.
   *
   * @param entityName nom d'entité JPA du porteur
   * @param attribute nom de l'attribut {@code @ElementCollection}
   * @return nom physique ; null si stratégie inconnue
   */
  public String collectionTable(String entityName, String attribute) {
    return implicit(entityName + "_" + attribute);
  }

  private static boolean isQuoted(String n) {
    return n.length() > 1 && ((n.startsWith("`") && n.endsWith("`")) || (n.startsWith("\"") && n.endsWith("\"")));
  }

  private static String unquote(String n) {
    return isQuoted(n) ? n.substring(1, n.length() - 1) : n;
  }

  /** Algorithme de {@code CamelCaseToUnderscoresNamingStrategy}. */
  static String snake(String name) {
    StringBuilder b = new StringBuilder(name.replace('.', '_'));
    for (int i = 1; i < b.length() - 1; i++) {
      char before = b.charAt(i - 1);
      char current = b.charAt(i);
      char after = b.charAt(i + 1);
      if ((Character.isLowerCase(before) || Character.isDigit(before)) && Character.isUpperCase(current)
          && (Character.isLowerCase(after) || Character.isDigit(after))) {
        b.insert(i++, '_');
      }
    }
    return b.toString().toLowerCase(Locale.ROOT);
  }
}
