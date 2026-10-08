package fr.cafat.meta.extract.relations;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.PersistenceExtractor;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.model.Column;
import fr.cafat.meta.model.Entity;
import fr.cafat.meta.model.Relation;
import fr.cafat.meta.model.TableRef;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelationExtractorTest {

  private static final String APP = "s-gen-fixture:fr.cafat.gpp.pg.domain.";
  private static ExtractionContext spring;
  private static PersistenceExtractor.Result result;

  @BeforeAll
  static void load() {
    spring = TestContexts.ofRepo(TestContexts.fixture("fixture-spring"), "s-gen-fixture",
        NamingStrategy.springBoot());
    result = PersistenceExtractor.run(spring);
  }

  static Relation relation(List<Relation> relations, String id) {
    return relations.stream().filter(r -> r.id().equals(id)).findFirst()
        .orElseThrow(() -> new AssertionError(id + " absente : " + relations.stream().map(Relation::id).toList()));
  }

  static Entity entity(PersistenceExtractor.Result r, String fqn) {
    return r.drafts().get(fqn).toEntity();
  }

  static Column column(Entity e, String name) {
    return e.columns().stream().filter(c -> name.equals(c.name())).findFirst()
        .orElseThrow(() -> new AssertionError(name + " absente : " + e.columns().stream().map(Column::name).toList()));
  }

  @Test
  void manyToOneProprietaireEtSonInverse() {
    Relation owner = relation(result.relations(), APP + "MoyenContact.personnePhysique");
    assertThat(owner.type()).isEqualTo("MANY_TO_ONE");
    assertThat(owner.owningSide()).isTrue();
    assertThat(owner.toEntity()).isEqualTo(APP + "PersonnePhysique");
    assertThat(owner.toClass()).isEqualTo("fr.cafat.gpp.pg.domain.PersonnePhysique");
    assertThat(owner.joinColumns()).containsExactly("fk_personne_physique");
    assertThat(owner.joinTable()).isNull();
    assertThat(owner.optional()).isFalse();

    Relation inverse = relation(result.relations(), APP + "PersonnePhysique.moyensContact");
    assertThat(inverse.type()).isEqualTo("ONE_TO_MANY");
    assertThat(inverse.owningSide()).isFalse();
    assertThat(inverse.mappedBy()).isEqualTo("personnePhysique");
    assertThat(inverse.toEntity()).isEqualTo(APP + "MoyenContact");
    assertThat(inverse.joinColumns()).containsExactly("fk_personne_physique");
    assertThat(inverse.optional()).isNull();

    Column fk = column(entity(result, "fr.cafat.gpp.pg.domain.MoyenContact"), "fk_personne_physique");
    assertThat(fk.kind()).isEqualTo("join");
    assertThat(fk.references()).isEqualTo(APP + "PersonnePhysique");
    assertThat(fk.nullable()).isFalse();
    assertThat(fk.table()).isEqualTo("gpp_moyen_contact");
    assertThat(fk.field()).isEqualTo("personnePhysique");

    Entity tel = entity(result, "fr.cafat.gpp.pg.domain.Telephone");
    assertThat(column(tel, "fk_personne_physique").table()).isEqualTo("gpp_moyen_contact");
    assertThat(result.relations()).extracting(Relation::fromEntity).doesNotContain(APP + "Telephone");
  }

  @Test
  void manyToManyAvecTableDeJointure() {
    Relation owner = relation(result.relations(), APP + "PersonnePhysique.groupes");
    assertThat(owner.type()).isEqualTo("MANY_TO_MANY");
    assertThat(owner.joinTable()).isEqualTo(new TableRef("sgengpp", "gpp_personne_groupe"));
    assertThat(owner.joinColumns()).containsExactly("fk_personne");
    assertThat(owner.inverseJoinColumns()).containsExactly("fk_groupe");

    Relation inverse = relation(result.relations(), APP + "Groupe.membres");
    assertThat(inverse.owningSide()).isFalse();
    assertThat(inverse.joinTable()).isEqualTo(new TableRef("sgengpp", "gpp_personne_groupe"));
    assertThat(inverse.joinColumns()).containsExactly("fk_groupe");
    assertThat(inverse.inverseJoinColumns()).containsExactly("fk_personne");
  }

  @Test
  void oneToOneEtCollectionDeValeurs() {
    Relation dossier = relation(result.relations(), APP + "PersonnePhysique.dossier");
    assertThat(dossier.type()).isEqualTo("ONE_TO_ONE");
    assertThat(dossier.joinColumns()).containsExactly("fk_dossier");
    assertThat(dossier.toClass()).isEqualTo("fr.cafat.gpp.pg.domain.Dossier");
    assertThat(column(entity(result, "fr.cafat.gpp.pg.domain.PersonnePhysique"), "fk_dossier").kind())
        .isEqualTo("join");

    Relation alias = relation(result.relations(), APP + "PersonnePhysique.alias");
    assertThat(alias.type()).isEqualTo("ELEMENT_COLLECTION");
    assertThat(alias.owningSide()).isTrue();
    assertThat(alias.toClass()).isEqualTo("String");
    assertThat(alias.toEntity()).isNull();
    assertThat(alias.joinTable()).isEqualTo(new TableRef("sgengpp", "gpp_personne_alias"));
    assertThat(alias.joinColumns()).containsExactly("fk_personne");
    assertThat(spring.diagnostics().all()).noneMatch(d -> d.message().contains(".alias"));
  }

  @Test
  void jointureImpliciteSansNommageSpring() {
    ExtractionContext jboss = TestContexts.ofRepo(TestContexts.fixture("fixture-jboss-multi"), "legacy",
        NamingStrategy.jpa());
    PersistenceExtractor.Result r = PersistenceExtractor.run(jboss);
    Relation assure = relation(r.relations(), "legacy:fr.cafat.legacy.domain.Contrat.assure");
    assertThat(assure.joinColumns()).containsExactly("assure_NUMERO");
    assertThat(assure.optional()).isNull();
    Column fk = column(entity(r, "fr.cafat.legacy.domain.Contrat"), "assure_NUMERO");
    assertThat(fk.references()).isEqualTo("legacy:fr.cafat.legacy.domain.Assure");
    assertThat(fk.table()).isEqualTo("Contrat");
    assertThat(fk.nullable()).isNull();
  }

  @Test
  void relationsDeMappedSuperclassRepeteesEtImplicites(@TempDir Path dir) {
    ExtractionContext ctx = TestContexts.ofSources(dir, NamingStrategy.springBoot(), """
        package t;
        import jakarta.persistence.*;
        @MappedSuperclass
        public abstract class Trace {
          @Id private Long id;
          @ManyToOne private Agent auteur;
        }
        """, """
        package t;
        import jakarta.persistence.*;
        @Entity public class Agent { @Id private String matricule; }
        """, """
        package t;
        import jakarta.persistence.*;
        import java.util.*;
        @Entity @Table(name = "DOSSIER")
        public class Dossier extends Trace {
          @ManyToMany private Set<Agent> instructeurs;
          @OneToMany @JoinColumn(name = "ID_DOSSIER") private List<Piece> pieces;
          @OneToMany(targetEntity = Piece.class) private List annexes;
          @ManyToOne private fr.ext.Inconnu inconnu;
        }
        """, """
        package t;
        import jakarta.persistence.*;
        @Entity public class Piece extends Trace { }
        """);
    PersistenceExtractor.Result r = PersistenceExtractor.run(ctx);
    Relation auteurDossier = relation(r.relations(), "app:t.Dossier.auteur");
    assertThat(auteurDossier.inheritedFrom()).isEqualTo("app:t.Trace");
    assertThat(auteurDossier.joinColumns()).containsExactly("auteur_matricule");
    assertThat(relation(r.relations(), "app:t.Piece.auteur").inheritedFrom()).isEqualTo("app:t.Trace");
    assertThat(r.relations()).extracting(Relation::fromEntity).doesNotContain("app:t.Trace");

    Column fk = column(entity(r, "t.Dossier"), "auteur_matricule");
    assertThat(fk.table()).isEqualTo("dossier");
    assertThat(fk.inheritedFrom()).isEqualTo("app:t.Trace");

    Relation instructeurs = relation(r.relations(), "app:t.Dossier.instructeurs");
    assertThat(instructeurs.joinTable()).isEqualTo(new TableRef(null, "dossier_instructeurs"));
    assertThat(instructeurs.joinColumns()).containsExactly("dossier_id");
    assertThat(instructeurs.inverseJoinColumns()).containsExactly("instructeurs_matricule");

    Relation pieces = relation(r.relations(), "app:t.Dossier.pieces");
    assertThat(pieces.joinTable()).isNull();
    assertThat(pieces.joinColumns()).containsExactly("id_dossier");

    assertThat(relation(r.relations(), "app:t.Dossier.annexes").toEntity()).isEqualTo("app:t.Piece");
    Relation inconnu = relation(r.relations(), "app:t.Dossier.inconnu");
    assertThat(inconnu.toEntity()).isNull();
    assertThat(inconnu.joinColumns()).isNull();
    assertThat(TestContexts.codes(ctx)).containsOnlyOnce("RELATION_TARGET_NOT_FOUND");
  }
}
