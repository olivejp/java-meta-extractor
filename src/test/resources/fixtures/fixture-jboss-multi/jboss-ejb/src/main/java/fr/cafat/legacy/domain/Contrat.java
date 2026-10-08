package fr.cafat.legacy.domain;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.util.Date;

@Entity
public class Contrat {

  @Id
  private Long idContrat;

  @Temporal(TemporalType.DATE)
  private Date dateEffet;

  @ManyToOne
  private Assure assure;
}
