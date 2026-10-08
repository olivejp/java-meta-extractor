package fr.cafat.meta.extract.entities;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NamingStrategyTest {

  @Test
  void snakeCaseCommeHibernate() {
    assertThat(NamingStrategy.snake("numeroCafat")).isEqualTo("numero_cafat");
    assertThat(NamingStrategy.snake("PGUnion")).isEqualTo("pgunion");
    assertThat(NamingStrategy.snake("adresseLigne1")).isEqualTo("adresse_ligne1");
    assertThat(NamingStrategy.snake("DTYPE")).isEqualTo("dtype");
    assertThat(NamingStrategy.snake("adresse.codePostal")).isEqualTo("adresse_code_postal");
    assertThat(NamingStrategy.snake("GPP_PERSONNE")).isEqualTo("gpp_personne");
  }

  @Test
  void identifiantsCitesConserves() {
    NamingStrategy n = NamingStrategy.springBoot();
    assertThat(n.explicit("`MaTable`")).isEqualTo("MaTable");
    assertThat(n.explicit("\"MGENGPP\"")).isEqualTo("MGENGPP");
    assertThat(n.explicit("MGENGPP")).isEqualTo("mgengpp");
    assertThat(NamingStrategy.jpa().explicit("MGENGPP")).isEqualTo("MGENGPP");
  }

  @Test
  void nomsImplicites() {
    NamingStrategy spring = NamingStrategy.springBoot();
    assertThat(spring.joinColumn("personnePhysique", "numero_interne")).isEqualTo("personne_physique_numero_interne");
    assertThat(spring.joinTable("gpp_personne", "groupes", "gpp_groupe")).isEqualTo("gpp_personne_groupes");
    assertThat(NamingStrategy.jpa().joinTable("PERSONNE", "groupes", "GROUPE")).isEqualTo("PERSONNE_GROUPE");
    assertThat(spring.collectionTable("PersonnePhysique", "alias")).isEqualTo("personne_physique_alias");
    NamingStrategy custom = new NamingStrategy(NamingStrategy.Physical.UNKNOWN, true);
    assertThat(custom.implicit("nom")).isNull();
    assertThat(custom.explicit("NOM")).isEqualTo("NOM");
  }
}
