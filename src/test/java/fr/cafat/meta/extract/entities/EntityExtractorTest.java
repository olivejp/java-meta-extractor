package fr.cafat.meta.extract.entities;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.model.Column;
import fr.cafat.meta.model.Entity;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityExtractorTest {

  private static final String PG = "fr.cafat.gpp.pg.domain.";
  private static ExtractionContext spring;
  private static Map<String, EntityDraft> drafts;

  @BeforeAll
  static void load() {
    spring = TestContexts.ofRepo(TestContexts.fixture("fixture-spring"), "s-gen-fixture",
        NamingStrategy.springBoot());
    drafts = TestContexts.entities(spring);
  }

  static Entity entity(Map<String, EntityDraft> drafts, String fqn) {
    EntityDraft d = drafts.get(fqn);
    assertThat(d).as(fqn).isNotNull();
    return d.toEntity();
  }

  static Column column(Entity e, String name) {
    return e.columns().stream().filter(c -> name.equals(c.name())).findFirst()
        .orElseThrow(() -> new AssertionError("colonne " + name + " absente de " + e.className() + " : "
            + e.columns().stream().map(Column::name).toList()));
  }

  @Test
  void typesPersistantsDetectes() {
    assertThat(drafts.keySet()).contains(PG + "PersonnePhysique", PG + "AbstractAuditEntity", PG + "Adresse",
        PG + "MoyenContact", PG + "Telephone", PG + "Courriel", PG + "Evenement", PG + "Naissance",
        PG + "Deces", PG + "Groupe", PG + "PGUnion", PG + "ArchiveObsolete",
        "fr.cafat.gpp.db2.domain.Union", "fr.cafat.gpp.db2.domain.GeoCommune");
    assertThat(drafts.keySet()).noneMatch(k -> k.contains("TestOnly"));
    assertThat(drafts.keySet()).doesNotContain(PG + "Civilite");
  }

  @Test
  void entiteAvecTableExplicite() {
    Entity p = entity(drafts, PG + "PersonnePhysique");
    assertThat(p.id()).isEqualTo("s-gen-fixture:" + PG + "PersonnePhysique");
    assertThat(p.kind()).isEqualTo("entity");
    assertThat(p.name()).isEqualTo("PersonnePhysique");
    assertThat(p.schema()).isEqualTo("sgengpp");
    assertThat(p.table()).isEqualTo("gpp_personne_physique");
    assertThat(p.parent()).isEqualTo("s-gen-fixture:" + PG + "AbstractAuditEntity");
    assertThat(p.inheritance()).isNull();
    assertThat(p.source().file()).isEqualTo("src/main/java/fr/cafat/gpp/pg/domain/PersonnePhysique.java");

    Column id = column(p, "numero_interne");
    assertThat(id.kind()).isEqualTo("id");
    assertThat(id.pk()).isTrue();
    assertThat(id.nullable()).isFalse();
    assertThat(id.unique()).isTrue();
    assertThat(id.generated()).isEqualTo("IDENTITY");
    assertThat(id.table()).isEqualTo("gpp_personne_physique");
    assertThat(id.field()).isEqualTo("numeroInterne");

    Column nom = column(p, "nom");
    assertThat(nom.length()).isEqualTo(80);
    assertThat(nom.nullable()).isFalse();
    assertThat(nom.unique()).isNull();

    Column cafat = column(p, "numero_cafat");
    assertThat(cafat.unique()).isTrue();
    assertThat(cafat.length()).isEqualTo(12);
    assertThat(cafat.nullable()).isNull();

    Column civ = column(p, "civilite");
    assertThat(civ.enumValues()).containsExactly("M", "MME", "NON_PRECISE");
    assertThat(civ.enumStorage()).isEqualTo("STRING");
  }

  @Test
  void transientsExclus() {
    Entity p = entity(drafts, PG + "PersonnePhysique");
    assertThat(p.columns()).extracting(Column::field)
        .doesNotContain("libelleCalcule", "cache", "serialVersionUID", "moyensContact", "groupes", "alias");
  }

  @Test
  void embeddableDeplieAvecSurcharge() {
    Entity p = entity(drafts, PG + "PersonnePhysique");
    assertThat(column(p, "rue").field()).isEqualTo("adresse.rue");
    assertThat(column(p, "ville_residence").field()).isEqualTo("adresse.ville");
    Column cp = column(p, "code_postal");
    assertThat(cp.field()).isEqualTo("adresse.codePostal");
    assertThat(cp.length()).isEqualTo(5);

    Entity a = entity(drafts, PG + "Adresse");
    assertThat(a.kind()).isEqualTo("embeddable");
    assertThat(a.name()).isNull();
    assertThat(a.table()).isNull();
    assertThat(column(a, "ville").table()).isNull();
  }

  @Test
  void colonnesDuMappedSuperclassRecopiees() {
    Entity p = entity(drafts, PG + "PersonnePhysique");
    String msc = "s-gen-fixture:" + PG + "AbstractAuditEntity";
    Column creation = column(p, "date_creation");
    assertThat(creation.inheritedFrom()).isEqualTo(msc);
    assertThat(creation.table()).isEqualTo("gpp_personne_physique");
    assertThat(creation.nullable()).isFalse();
    assertThat(column(p, "created_by").inheritedFrom()).isEqualTo(msc);
    assertThat(column(p, "version").kind()).isEqualTo("version");
    assertThat(column(p, "nom").inheritedFrom()).isNull();

    Entity m = entity(drafts, PG + "AbstractAuditEntity");
    assertThat(m.kind()).isEqualTo("mapped_superclass");
    assertThat(m.table()).isNull();
    assertThat(m.name()).isNull();
  }

  @Test
  void heritageJoined() {
    String root = "s-gen-fixture:" + PG + "MoyenContact";
    Entity mc = entity(drafts, PG + "MoyenContact");
    assertThat(mc.inheritance().strategy()).isEqualTo("JOINED");
    assertThat(mc.inheritance().root()).isEqualTo(root);
    assertThat(mc.inheritance().discriminator().column()).isEqualTo("type_contact");
    assertThat(mc.inheritance().discriminator().value()).isNull();
    assertThat(mc.inheritance().joinColumns()).isNull();
    assertThat(column(mc, "type_contact").kind()).isEqualTo("discriminator");

    Entity tel = entity(drafts, PG + "Telephone");
    assertThat(tel.parent()).isEqualTo(root);
    assertThat(tel.table()).isEqualTo("gpp_telephone");
    assertThat(tel.inheritance().strategy()).isEqualTo("JOINED");
    assertThat(tel.inheritance().root()).isEqualTo(root);
    assertThat(tel.inheritance().discriminator().value()).isEqualTo("TEL");
    assertThat(tel.inheritance().joinColumns()).containsExactly("id_moyen_contact");

    Column pkJoin = column(tel, "id_moyen_contact");
    assertThat(pkJoin.kind()).isEqualTo("pk_join");
    assertThat(pkJoin.pk()).isTrue();
    assertThat(pkJoin.unique()).isTrue();
    assertThat(pkJoin.table()).isEqualTo("gpp_telephone");
    assertThat(pkJoin.references()).isEqualTo(root);

    Column inheritedId = column(tel, "id");
    assertThat(inheritedId.table()).isEqualTo("gpp_moyen_contact");
    assertThat(inheritedId.inheritedFrom()).isEqualTo(root);
    assertThat(column(tel, "numero").table()).isEqualTo("gpp_telephone");
    assertThat(column(tel, "numero").length()).isEqualTo(20);

    Entity mel = entity(drafts, PG + "Courriel");
    assertThat(mel.inheritance().joinColumns()).containsExactly("id");
    assertThat(column(mel, "adresse_mail").table()).isEqualTo("gpp_courriel");
  }

  @Test
  void heritageSingleTable() {
    String root = "s-gen-fixture:" + PG + "Evenement";
    Entity evt = entity(drafts, PG + "Evenement");
    assertThat(evt.inheritance().strategy()).isEqualTo("SINGLE_TABLE");
    Column disc = column(evt, "type_evt");
    assertThat(disc.kind()).isEqualTo("discriminator");
    assertThat(disc.length()).isEqualTo(3);
    assertThat(disc.table()).isEqualTo("gpp_evenement");
    assertThat(column(evt, "id").generated()).isEqualTo("SEQUENCE");

    Entity nai = entity(drafts, PG + "Naissance");
    assertThat(nai.table()).isEqualTo("gpp_evenement");
    assertThat(nai.schema()).isEqualTo("sgengpp");
    assertThat(nai.inheritance().root()).isEqualTo(root);
    assertThat(nai.inheritance().discriminator().value()).isEqualTo("NAI");
    assertThat(nai.inheritance().joinColumns()).isNull();
    assertThat(column(nai, "lieu_naissance").table()).isEqualTo("gpp_evenement");
    assertThat(column(nai, "type_evt").inheritedFrom()).isEqualTo(root);
    assertThat(column(nai, "date_evenement").inheritedFrom()).isEqualTo(root);

    Entity deces = entity(drafts, PG + "Deces");
    Column capital = column(deces, "capital_deces");
    assertThat(capital.precision()).isEqualTo(10);
    assertThat(capital.scale()).isEqualTo(2);
  }

  @Test
  void tableImpliciteSelonLeNommage() {
    assertThat(entity(drafts, PG + "ArchiveObsolete").table()).isEqualTo("archive_obsolete");
    assertThat(entity(drafts, "fr.cafat.gpp.db2.domain.Union").table()).isEqualTo("v_union");
    assertThat(entity(drafts, "fr.cafat.gpp.db2.domain.Union").schema()).isEqualTo("mgengpp");
  }

  @Test
  void depotJbossAvecParentExterne() {
    ExtractionContext jboss = TestContexts.ofRepo(TestContexts.fixture("fixture-jboss-multi"), "legacy",
        NamingStrategy.jpa());
    Map<String, EntityDraft> d = TestContexts.entities(jboss);
    Entity assure = entity(d, "fr.cafat.legacy.domain.Assure");
    assertThat(assure.parent()).isEqualTo("fr.cafat.commun.persistence.AbstractEntite");
    assertThat(assure.table()).isEqualTo("ASSURE");
    assertThat(column(assure, "NUMERO").length()).isEqualTo(13);
    assertThat(TestContexts.codes(jboss)).contains("PARENT_NOT_FOUND");

    Entity contrat = entity(d, "fr.cafat.legacy.domain.Contrat");
    assertThat(contrat.table()).isEqualTo("Contrat");
    assertThat(contrat.parent()).isNull();
    assertThat(column(contrat, "idContrat").pk()).isTrue();
    assertThat(column(contrat, "dateEffet").kind()).isEqualTo("basic");
    assertThat(d).doesNotContainKey("fr.cafat.legacy.dao.ContratDaoIT");
  }

  @Test
  void accesParProprieteEtIdEmbarque(@TempDir Path dir) {
    ExtractionContext ctx = TestContexts.ofSources(dir, NamingStrategy.springBoot(), """
        package t;
        import jakarta.persistence.*;
        @Entity
        public class Ligne {
          private Long numero;
          @Id @Column(name = "NUM_LIGNE")
          public Long getNumero() { return numero; }
          public String getLibelleCourt() { return null; }
          @Transient public String getCalcul() { return null; }
          public static String getStatique() { return null; }
          public void setNumero(Long n) { numero = n; }
        }
        """, """
        package t;
        import jakarta.persistence.*;
        @Embeddable
        public class CleCompte {
          private String agence;
          private Integer rang;
        }
        """, """
        package t;
        import jakarta.persistence.*;
        @Entity @Table(name = "COMPTE")
        public class Compte {
          @EmbeddedId private CleCompte cle;
          @Basic(optional = false) private String titulaire;
          @Enumerated private Etat etat;
          @Formula("1") private int calcule;
          @Embedded private fr.ext.Externe ext;
        }
        """, """
        package t;
        public enum Etat { OUVERT, CLOS }
        """);
    Map<String, EntityDraft> d = TestContexts.entities(ctx);

    Entity ligne = entity(d, "t.Ligne");
    assertThat(ligne.table()).isEqualTo("ligne");
    assertThat(ligne.columns()).extracting(Column::name).containsExactly("libelle_court", "num_ligne");
    assertThat(column(ligne, "num_ligne").pk()).isTrue();

    Entity compte = entity(d, "t.Compte");
    assertThat(compte.table()).isEqualTo("compte");
    assertThat(column(compte, "agence").pk()).isTrue();
    assertThat(column(compte, "agence").unique()).isNull();
    assertThat(column(compte, "rang").field()).isEqualTo("cle.rang");
    assertThat(column(compte, "titulaire").nullable()).isFalse();
    assertThat(column(compte, "etat").enumStorage()).isEqualTo("ORDINAL");
    assertThat(column(compte, "etat").enumValues()).containsExactly("OUVERT", "CLOS");
    assertThat(compte.columns()).extracting(Column::field).doesNotContain("calcule");
    assertThat(TestContexts.codes(ctx)).contains("EMBEDDABLE_NOT_FOUND");
  }

  @Test
  void surchargeDeClasseEtSingleTableImplicite(@TempDir Path dir) {
    ExtractionContext ctx = TestContexts.ofSources(dir, NamingStrategy.springBoot(), """
        package t;
        import javax.persistence.*;
        @MappedSuperclass
        public abstract class Base {
          @Id private Long id;
          private String codeAgent;
        }
        """, """
        package t;
        import javax.persistence.*;
        @Entity
        @AttributeOverride(name = "codeAgent", column = @Column(name = "AGENT", length = 8))
        public class Animal extends Base {
        }
        """, """
        package t;
        import javax.persistence.*;
        @Entity
        public class Chien extends Animal {
          private String race;
        }
        """, """
        package t;
        public class Intermediaire extends Animal {
        }
        """, """
        package t;
        import javax.persistence.*;
        @Entity(name = "Felin")
        public class Chat extends Intermediaire {
        }
        """);
    Map<String, EntityDraft> d = TestContexts.entities(ctx);
    Entity animal = entity(d, "t.Animal");
    assertThat(animal.inheritance().strategy()).isEqualTo("SINGLE_TABLE");
    assertThat(animal.inheritance().discriminator().column()).isEqualTo("dtype");
    assertThat(animal.inheritance().discriminator().value()).isEqualTo("Animal");
    Column agent = column(animal, "agent");
    assertThat(agent.length()).isEqualTo(8);
    assertThat(agent.inheritedFrom()).isEqualTo("app:t.Base");

    Entity chat = entity(d, "t.Chat");
    assertThat(chat.parent()).isEqualTo("app:t.Animal");
    assertThat(chat.table()).isEqualTo("animal");
    assertThat(chat.inheritance().discriminator().value()).isEqualTo("Felin");
    assertThat(column(chat, "dtype").inheritedFrom()).isEqualTo("app:t.Animal");
    assertThat(column(entity(d, "t.Chien"), "race").table()).isEqualTo("animal");
  }
}
