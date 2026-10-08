package fr.cafat.gpp.db2.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "GEO_COMMUNE", schema = "CAFGEO")
public class GeoCommune {

  @Id
  @Column(name = "CODE_COMMUNE", length = 5)
  private String code;

  @Column(name = "LIBELLE")
  private String libelle;
}
