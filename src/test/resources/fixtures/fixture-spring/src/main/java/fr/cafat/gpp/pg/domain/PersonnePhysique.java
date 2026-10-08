package fr.cafat.gpp.pg.domain;

import jakarta.persistence.*;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "gpp_personne_physique", schema = "sgengpp")
public class PersonnePhysique extends AbstractAuditEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "numero_interne")
  private Long numeroInterne;

  @Column(length = 80, nullable = false)
  private String nom;

  @Column(name = "numero_cafat", unique = true, length = 12)
  private String numeroCafat;

  @Enumerated(EnumType.STRING)
  private Civilite civilite;

  @Embedded
  @AttributeOverride(name = "ville", column = @Column(name = "ville_residence"))
  private Adresse adresse;

  @OneToMany(mappedBy = "personnePhysique", cascade = CascadeType.ALL)
  private List<MoyenContact> moyensContact;

  @ManyToMany
  @JoinTable(name = "gpp_personne_groupe", schema = "sgengpp",
      joinColumns = @JoinColumn(name = "fk_personne"),
      inverseJoinColumns = @JoinColumn(name = "fk_groupe"))
  private Set<Groupe> groupes;

  @OneToOne
  @JoinColumn(name = "fk_dossier")
  private Dossier dossier;

  @ElementCollection
  @CollectionTable(name = "gpp_personne_alias", schema = "sgengpp", joinColumns = @JoinColumn(name = "fk_personne"))
  @Column(name = "alias")
  private List<String> alias;

  @Transient
  private String libelleCalcule;

  private transient int cache;

  private static final long serialVersionUID = 1L;
}
