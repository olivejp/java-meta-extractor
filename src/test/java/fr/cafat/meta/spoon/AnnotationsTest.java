package fr.cafat.meta.spoon;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.NamingStrategy;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;

class AnnotationsTest {

  private static final Set<String> STEREOTYPE = Set.of("org.springframework.stereotype");

  @TempDir
  static Path dir;
  private static ExtractionContext ctx;

  @BeforeAll
  static void load() {
    ctx = TestContexts.ofSources(dir, NamingStrategy.jpa(), """
        package demo;

        import javax.persistence.Column;
        import javax.persistence.FetchType;
        import javax.persistence.JoinColumn;
        import javax.persistence.JoinTable;
        import javax.persistence.ManyToMany;
        import javax.ws.rs.Path;
        import org.springframework.web.bind.annotation.GetMapping;

        public class Explicite {
          @Column(name = "C", nullable = false, length = 12)
          String colonne;

          @ManyToMany(fetch = FetchType.LAZY, targetEntity = Explicite.class)
          @JoinTable(name = "J", joinColumns = {@JoinColumn(name = "A"), @JoinColumn(name = "B")})
          java.util.List<Explicite> liens;

          @GetMapping(value = {}, path = {"/a", "/b"})
          void get() { }

          @Path("/p")
          void jaxrs() { }
        }
        """, """
        package demo;

        import org.springframework.web.bind.annotation.*;

        @RequestMapping("/racine")
        public class Etoile { }
        """, """
        package demo;

        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import org.springframework.stereotype.Controller;

        @Controller
        @Retention(RetentionPolicy.RUNTIME)
        public @interface MonControleur { }
        """, """
        package demo;

        @MonControleur
        public class Meta { }
        """);
  }

  private static CtType<?> type(String name) {
    return ctx.types().get("demo." + name);
  }

  private static CtElement field(String name) {
    return type("Explicite").getField(name);
  }

  private static CtElement method(String name) {
    return type("Explicite").getMethodsByName(name).get(0);
  }

  @Test
  void filtrageParPaquet() {
    assertThat(Annotations.find(method("get"), Annotations.SPRING_WEB, "GetMapping")).isNotNull();
    assertThat(Annotations.find(method("get"), Annotations.JAXRS, "GetMapping")).isNull();
    assertThat(Annotations.find(method("get"), Annotations.ANY, "GetMapping")).isNotNull();
    assertThat(Annotations.has(method("jaxrs"), Annotations.JAXRS, "Path")).isTrue();
    assertThat(Annotations.has(method("jaxrs"), Annotations.SPRING_WEB, "Path")).isFalse();
    assertThat(Annotations.find(null, Annotations.JPA, "Column")).isNull();
  }

  @Test
  void importEtoileAccepteParNomSimple() {
    assertThat(Annotations.find(type("Etoile"), Annotations.SPRING_WEB, "RequestMapping")).isNotNull();
  }

  @Test
  void premiereTrouveeDansLOrdreDesNoms() {
    CtAnnotation<?> a = Annotations.findAny(field("liens"), Annotations.JPA, "OneToMany", "ManyToMany", "JoinTable");
    assertThat(a.getAnnotationType().getSimpleName()).isEqualTo("ManyToMany");
    assertThat(Annotations.findAny(field("liens"), Annotations.JPA, "OneToOne")).isNull();
  }

  @Test
  void valeursDesAttributs() {
    CtAnnotation<?> column = Annotations.find(field("colonne"), Annotations.JPA, "Column");
    assertThat(ctx.eval().constant(Annotations.value(column, "name"))).isEqualTo("C");
    assertThat(Annotations.value(column, "absent")).isNull();
    assertThat(Annotations.value(null, "name")).isNull();
    assertThat(Annotations.bool(column, "nullable")).isFalse();
    assertThat(Annotations.bool(column, "unique")).isNull();
    assertThat(Annotations.integer(column, "length")).isEqualTo(12);
    assertThat(Annotations.integer(column, "precision")).isNull();
  }

  @Test
  void tableauxEtPremiereValeurNonVide() {
    CtAnnotation<?> get = Annotations.find(method("get"), Annotations.SPRING_WEB, "GetMapping");
    assertThat(Annotations.values(get, "path")).extracting(ctx.eval()::constant).containsExactly("/a", "/b");
    assertThat(Annotations.values(get, "value")).isEmpty();
    assertThat(Annotations.values(get, "absent")).isEmpty();
    assertThat(Annotations.values(Annotations.find(type("Etoile"), Annotations.SPRING_WEB, "RequestMapping"),
        "value")).hasSize(1);
    assertThat(Annotations.firstValue(get, "value", "path")).isSameAs(Annotations.value(get, "path"));
    assertThat(Annotations.firstValue(get, "absent")).isNull();
  }

  @Test
  void annotationsImbriqueesEtConstantes() {
    CtAnnotation<?> table = Annotations.find(field("liens"), Annotations.JPA, "JoinTable");
    assertThat(Annotations.nested(table, "joinColumns"))
        .extracting(a -> ctx.eval().constant(Annotations.value(a, "name"))).containsExactly("A", "B");
    assertThat(Annotations.nested(table, "name")).isEmpty();
    CtAnnotation<?> many = Annotations.find(field("liens"), Annotations.JPA, "ManyToMany");
    assertThat(Annotations.enumConstant(Annotations.value(many, "fetch"))).isEqualTo("LAZY");
    assertThat(Annotations.enumConstant(Annotations.value(many, "targetEntity"))).isNull();
    assertThat(Annotations.classLiteral(Annotations.value(many, "targetEntity")).getQualifiedName())
        .isEqualTo("demo.Explicite");
    assertThat(Annotations.classLiteral(Annotations.value(many, "fetch"))).isNull();
  }

  @Test
  void metaAnnotation() {
    assertThat(Annotations.hasMeta(type("Meta"), ctx.types(), STEREOTYPE, "Controller")).isTrue();
    assertThat(Annotations.has(type("Meta"), STEREOTYPE, "Controller")).isFalse();
    assertThat(Annotations.hasMeta(type("Etoile"), ctx.types(), STEREOTYPE, "Controller")).isFalse();
  }
}
