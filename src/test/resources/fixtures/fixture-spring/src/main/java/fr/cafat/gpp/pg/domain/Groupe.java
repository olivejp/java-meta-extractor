package fr.cafat.gpp.pg.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.Set;

@Entity
@Table(name = "gpp_groupe", schema = "sgengpp")
public class Groupe {

  @Id
  private Long id;

  private String libelle;

  @ManyToMany(mappedBy = "groupes")
  private Set<PersonnePhysique> membres;
}
