package fr.cafat.legacy.db2;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "VW_PERSONNE", schema = "MGENGPP")
public class VuePersonne {

  @Id
  @Column(name = "NUMERO_INTERNE")
  private Long numero;

  @Column(name = "NOM")
  private String nom;
}
