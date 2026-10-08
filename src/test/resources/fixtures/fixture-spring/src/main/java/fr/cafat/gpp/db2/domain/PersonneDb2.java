package fr.cafat.gpp.db2.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Lecture de la vue DB2 des personnes physiques. */
@Entity
@Table(name = "GPP_PERSONNE_PHYSIQUE", schema = "MGENGPP")
public class PersonneDb2 {

  @Id
  @Column(name = "NUMERO_INTERNE")
  private Long numeroInterne;

  @Column(name = "NOM")
  private String nom;
}
