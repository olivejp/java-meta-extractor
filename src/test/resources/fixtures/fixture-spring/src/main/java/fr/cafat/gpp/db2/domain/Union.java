package fr.cafat.gpp.db2.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Union familiale lue dans la vue DB2 (double mapping de PGUnion). */
@Entity
@Table(name = "V_UNION", schema = "MGENGPP")
public class Union {

  @Id
  @Column(name = "ID")
  private Long id;

  @Column(name = "LIBELLE", length = 50)
  private String libelle;
}
