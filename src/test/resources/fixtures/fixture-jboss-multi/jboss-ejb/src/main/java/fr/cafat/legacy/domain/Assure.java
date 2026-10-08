package fr.cafat.legacy.domain;

import fr.cafat.commun.persistence.AbstractEntite;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/** Le parent vient d'une bibliothèque CAFAT partagée, absente du dépôt. */
@Entity
@Table(name = "ASSURE")
public class Assure extends AbstractEntite {

  @Id
  @Column(name = "NUMERO", length = 13)
  private String numero;

  @Column(name = "NOM_USAGE")
  private String nomUsage;
}
