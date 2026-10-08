package fr.cafat.gpp.pg.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.Table;

/** Union familiale côté PostgreSQL (double mapping de fr.cafat.gpp.db2.domain.Union). */
@Entity
@Table(name = "gpp_union", schema = "sgengpp")
@NamedNativeQuery(name = "PGUnion.findActives",
    query = "SELECT * FROM sgengpp.gpp_union WHERE actif = 1", resultClass = PGUnion.class)
public class PGUnion {

  @Id
  private Long id;

  private String libelle;

  private Boolean actif;
}
