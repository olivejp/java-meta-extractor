package fr.cafat.gpp.pg.domain;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "gpp_courriel", schema = "sgengpp")
@DiscriminatorValue("MEL")
public class Courriel extends MoyenContact {

  private String adresseMail;
}
