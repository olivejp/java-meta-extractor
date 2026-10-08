package fr.cafat.gpp.pg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("NAI")
public class Naissance extends Evenement {

  @Column(name = "lieu_naissance", length = 60)
  private String lieu;
}
