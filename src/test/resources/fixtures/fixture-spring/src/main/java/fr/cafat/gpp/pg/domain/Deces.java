package fr.cafat.gpp.pg.domain;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import jakarta.persistence.Column;

@Entity
@DiscriminatorValue("DEC")
public class Deces extends Evenement {

  @Column(precision = 10, scale = 2)
  private BigDecimal capitalDeces;
}
