package fr.cafat.gpp.pg.domain;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "gpp_evenement", schema = "sgengpp")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type_evt", discriminatorType = DiscriminatorType.STRING, length = 3)
public abstract class Evenement {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_evt")
  @SequenceGenerator(name = "seq_evt", sequenceName = "sgengpp.seq_evenement")
  private Long id;

  private java.time.LocalDate dateEvenement;
}
