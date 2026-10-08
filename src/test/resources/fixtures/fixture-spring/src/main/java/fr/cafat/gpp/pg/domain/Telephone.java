package fr.cafat.gpp.pg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "gpp_telephone", schema = "sgengpp")
@DiscriminatorValue("TEL")
@PrimaryKeyJoinColumn(name = "id_moyen_contact")
public class Telephone extends MoyenContact {

  @Column(name = "numero", length = 20)
  private String numero;
}
