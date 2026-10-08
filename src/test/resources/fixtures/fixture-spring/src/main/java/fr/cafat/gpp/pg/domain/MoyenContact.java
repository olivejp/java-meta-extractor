package fr.cafat.gpp.pg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Racine de la hiérarchie des moyens de contact (une table par sous-classe). */
@Entity
@Table(name = "gpp_moyen_contact", schema = "sgengpp")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "type_contact")
public abstract class MoyenContact {

  @Id
  @Column(name = "id")
  private Long id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "fk_personne_physique", nullable = false)
  private PersonnePhysique personnePhysique;

  private Boolean principal;
}
