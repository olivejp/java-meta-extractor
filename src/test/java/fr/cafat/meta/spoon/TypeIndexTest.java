package fr.cafat.meta.spoon;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.NamingStrategy;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;

class TypeIndexTest {

  @TempDir
  static Path dir;
  private static TypeIndex index;
  private static Factory factory;

  @BeforeAll
  static void load() {
    ExtractionContext ctx = TestContexts.ofSources(dir, NamingStrategy.jpa(),
        "package demo; public class Base { public static class Interne { } }",
        "package demo; public class Fille extends Base { }",
        "package demo; public interface Service { }",
        "package demo; public class ServiceImpl implements Service { }",
        "package demo; public class Doublon { }",
        "package autre; public class Doublon { }");
    index = ctx.types();
    factory = ctx.model().getRootPackage().getFactory();
  }

  @Test
  void typesTriesAvecLesTypesImbriques() {
    assertThat(index.all()).extracting(CtType::getQualifiedName).containsExactly("autre.Doublon", "demo.Base",
        "demo.Base$Interne", "demo.Doublon", "demo.Fille", "demo.Service", "demo.ServiceImpl");
    assertThat(index.get("demo.Base$Interne")).isNotNull();
    assertThat(index.get("demo.Absent")).isNull();
    assertThat(index.get(null)).isNull();
  }

  @Test
  void resolutionParNomQualifiePuisNomSimpleUnique() {
    assertThat(index.resolve(factory.Type().createReference("demo.Fille")).getQualifiedName())
        .isEqualTo("demo.Fille");
    assertThat(index.resolve(factory.Type().createReference("inconnu.Fille")).getQualifiedName())
        .isEqualTo("demo.Fille");
    assertThat(index.resolve(factory.Type().createReference("inconnu.Doublon"))).isNull();
    assertThat(index.resolve(factory.Type().createReference("java.lang.String"))).isNull();
    assertThat(index.resolve(null)).isNull();
  }

  @Test
  void parNomSimple() {
    assertThat(index.bySimpleName("Doublon")).extracting(CtType::getQualifiedName)
        .containsExactlyInAnyOrder("autre.Doublon", "demo.Doublon");
    assertThat(index.bySimpleName("Absent")).isEmpty();
  }

  @Test
  void sousTypesDirects() {
    assertThat(index.directSubtypes(index.get("demo.Base"))).extracting(CtType::getQualifiedName)
        .containsExactly("demo.Fille");
    assertThat(index.directSubtypes(index.get("demo.Service"))).extracting(CtType::getQualifiedName)
        .containsExactly("demo.ServiceImpl");
    assertThat(index.directSubtypes(index.get("demo.Fille"))).isEmpty();
  }
}
