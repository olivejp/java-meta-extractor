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
import spoon.reflect.reference.CtTypeReference;

class TypesTest {

  @TempDir
  static Path dir;
  private static CtType<?> type;

  @BeforeAll
  static void load() {
    ExtractionContext ctx = TestContexts.ofSources(dir, NamingStrategy.jpa(), """
        package demo;

        import java.util.List;
        import java.util.Map;
        import java.util.Set;
        import org.springframework.http.ResponseEntity;
        import reactor.core.publisher.Mono;

        public class Champs {
          String texte;
          int entier;
          String[] tableau;
          List<String> liste;
          Set<Ligne> ensemble;
          Map<String, Long> table;
          Ligne ligne;
          ResponseEntity<Mono<Ligne>> reponse;
          ResponseEntity<?> joker;
          void rien() { }
          Void rienObjet() { return null; }
          String quelqueChose() { return null; }
        }
        """, "package demo; public class Ligne { }");
    type = ctx.types().get("demo.Champs");
  }

  private static CtTypeReference<?> field(String name) {
    return type.getField(name).getType();
  }

  private static CtTypeReference<?> returned(String method) {
    return type.getMethodsByName(method).get(0).getType();
  }

  @Test
  void rendu() {
    assertThat(Types.render(field("texte"))).isEqualTo("String");
    assertThat(Types.render(field("entier"))).isEqualTo("int");
    assertThat(Types.render(field("tableau"))).isEqualTo("String[]");
    assertThat(Types.render(field("liste"))).isEqualTo("java.util.List<String>");
    assertThat(Types.render(field("table"))).isEqualTo("java.util.Map<String, Long>");
    assertThat(Types.render(field("joker"))).isEqualTo("org.springframework.http.ResponseEntity<?>");
    assertThat(Types.render(null)).isNull();
    assertThat(Types.baseName(field("ligne"))).isEqualTo("demo.Ligne");
    assertThat(Types.baseName(field("liste"))).isEqualTo("java.util.List");
  }

  @Test
  void nomsSimples() {
    assertThat(Types.simpleName(field("liste"))).isEqualTo("List");
    assertThat(Types.simpleName(null)).isNull();
    assertThat(Types.isNamed(field("ligne"), "Autre", "Ligne")).isTrue();
    assertThat(Types.isNamed(field("ligne"), "Autre")).isFalse();
    assertThat(Types.isNamed(null, "Ligne")).isFalse();
  }

  @Test
  void collectionsEtElements() {
    assertThat(Types.isCollection(field("liste"))).isTrue();
    assertThat(Types.isCollection(field("tableau"))).isTrue();
    assertThat(Types.isCollection(field("table"))).isTrue();
    assertThat(Types.isCollection(field("ligne"))).isFalse();
    assertThat(Types.isCollection(null)).isFalse();
    assertThat(Types.elementType(field("ensemble")).getQualifiedName()).isEqualTo("demo.Ligne");
    assertThat(Types.elementType(field("tableau")).getSimpleName()).isEqualTo("String");
    assertThat(Types.elementType(field("table")).getSimpleName()).isEqualTo("Long");
    assertThat(Types.elementType(field("ligne"))).isSameAs(field("ligne"));
    assertThat(Types.elementType(null)).isNull();
  }

  @Test
  void enveloppesDeReponse() {
    assertThat(Types.unwrapResponse(field("reponse")).getQualifiedName()).isEqualTo("demo.Ligne");
    assertThat(Types.unwrapResponse(field("joker"))).isNull();
    assertThat(Types.unwrapResponse(field("ligne"))).isSameAs(field("ligne"));
  }

  @Test
  void typesVides() {
    assertThat(Types.isVoid(returned("rien"))).isTrue();
    assertThat(Types.isVoid(returned("rienObjet"))).isTrue();
    assertThat(Types.isVoid(null)).isTrue();
    assertThat(Types.isVoid(returned("quelqueChose"))).isFalse();
  }

  @Test
  void typeDUneExpressionSansException() {
    assertThat(Types.typeOf(null)).isNull();
  }
}
