package fr.cafat.gpp.pg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class Adresse {

  private String rue;

  private String ville;

  @Column(name = "code_postal", length = 5)
  private String codePostal;
}
