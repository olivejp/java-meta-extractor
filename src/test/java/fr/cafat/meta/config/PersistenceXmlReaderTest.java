package fr.cafat.meta.config;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceXmlReaderTest {

  @TempDir
  Path dir;

  private final Diagnostics diags = new Diagnostics();

  private List<PersistenceUnit> read(String xml) throws IOException {
    Path f = dir.resolve("persistence.xml");
    Files.writeString(f, xml);
    return PersistenceXmlReader.read(f, "ejb/src/main/resources/META-INF/persistence.xml", dir, diags);
  }

  @Test
  void unitesDansLOrdreDuFichier() throws IOException {
    List<PersistenceUnit> units = read("""
        <?xml version="1.0" encoding="UTF-8"?>
        <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0">
          <persistence-unit name=" gppPU " transaction-type="JTA">
            <jta-data-source>java:jboss/datasources/GppDS</jta-data-source>
            <class>fr.cafat.Personne</class>
            <class>  </class>
            <class>fr.cafat.Adresse</class>
            <exclude-unlisted-classes>true</exclude-unlisted-classes>
            <properties>
              <property name="hibernate.default_schema" value="sgengpp"/>
              <property name="hibernate.dialect" value="org.hibernate.dialect.PostgreSQLDialect"/>
              <property value="sans-nom"/>
            </properties>
          </persistence-unit>
          <persistence-unit name="localPU" transaction-type="RESOURCE_LOCAL">
            <non-jta-data-source>java:/LocalDS</non-jta-data-source>
          </persistence-unit>
        </persistence>
        """);

    assertThat(units).extracting(PersistenceUnit::name).containsExactly("gppPU", "localPU");
    PersistenceUnit gpp = units.get(0);
    assertThat(gpp.jtaDataSource()).isEqualTo("java:jboss/datasources/GppDS");
    assertThat(gpp.nonJtaDataSource()).isNull();
    assertThat(gpp.classes()).containsExactly("fr.cafat.Personne", "fr.cafat.Adresse");
    assertThat(gpp.excludeUnlistedClasses()).isTrue();
    assertThat(gpp.properties()).containsOnlyKeys("hibernate.default_schema", "hibernate.dialect");
    assertThat(gpp.property("absente", "hibernate.default_schema")).isEqualTo("sgengpp");
    assertThat(gpp.file()).isEqualTo("ejb/src/main/resources/META-INF/persistence.xml");
    assertThat(gpp.line()).isEqualTo(3);
    assertThat(gpp.moduleDir()).isEqualTo(dir);

    PersistenceUnit local = units.get(1);
    assertThat(local.jtaDataSource()).isNull();
    assertThat(local.nonJtaDataSource()).isEqualTo("java:/LocalDS");
    assertThat(local.classes()).isEmpty();
    assertThat(local.excludeUnlistedClasses()).isFalse();
    assertThat(diags.all()).isEmpty();
  }

  @Test
  void excludeUnlistedClassesVideOuFaux() throws IOException {
    List<PersistenceUnit> units = read("""
        <persistence>
          <persistence-unit name="a"><exclude-unlisted-classes/></persistence-unit>
          <persistence-unit name="b"><exclude-unlisted-classes>false</exclude-unlisted-classes></persistence-unit>
        </persistence>
        """);

    assertThat(units).extracting(PersistenceUnit::excludeUnlistedClasses).containsExactly(true, false);
  }

  @Test
  void proprieteVideIgnoree() throws IOException {
    PersistenceUnit u = read("""
        <persistence><persistence-unit name="a"><properties>
          <property name="hibernate.default_schema" value="  "/>
        </properties></persistence-unit></persistence>
        """).get(0);

    assertThat(u.property("hibernate.default_schema")).isNull();
  }

  @Test
  void fichierIllisible() throws IOException {
    List<PersistenceUnit> units = read("<persistence><persistence-unit name='a'>");

    assertThat(units).isEmpty();
    assertThat(diags.all()).singleElement().satisfies(d -> {
      assertThat(d.code()).isEqualTo("CONFIG_PARSE_ERROR");
      assertThat(d.source().file()).isEqualTo("ejb/src/main/resources/META-INF/persistence.xml");
    });
    assertThat(diags.all()).extracting(Diagnostic::level).containsExactly(Diagnostics.ERROR);
  }
}
