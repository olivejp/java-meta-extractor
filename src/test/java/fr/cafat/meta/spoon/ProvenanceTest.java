package fr.cafat.meta.spoon;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.TestContexts;
import fr.cafat.meta.extract.ExtractionContext;
import fr.cafat.meta.extract.entities.NamingStrategy;
import fr.cafat.meta.model.Source;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

class ProvenanceTest {

  @TempDir
  static Path dir;
  private static ExtractionContext ctx;
  private static CtType<?> type;

  @BeforeAll
  static void load() {
    ctx = TestContexts.ofSources(dir, NamingStrategy.jpa(), """
        package demo;

        public class Service {

          private String url = "/u";

          public Service() {
            log("ctor");
          }

          @Deprecated
          public void appel() {
            Runnable r = () -> log("lambda");
          }

          public void suivant() { }

          static void log(String s) { }
        }
        """);
    type = ctx.types().get("demo.Service");
  }

  private static CtMethod<?> method(String name) {
    return type.getMethodsByName(name).get(0);
  }

  private static CtInvocation<?> logCall(String argument) {
    return type.getElements(new TypeFilter<CtInvocation<?>>(CtInvocation.class)).stream()
        .filter(i -> !i.getArguments().isEmpty() && i.getArguments().get(0) instanceof CtLiteral<?> l
            && argument.equals(l.getValue()))
        .findFirst().orElseThrow();
  }

  @Test
  void fichierRelatifEtLigneDuNom() {
    Source s = ctx.provenance().of(method("appel"));
    assertThat(s.className()).isEqualTo("demo.Service");
    assertThat(s.file()).isEqualTo("demo/Service.java");
    assertThat(s.line()).isEqualTo(12);
    assertThat(ctx.provenance().line(type.getField("url"))).isEqualTo(5);
    assertThat(ctx.provenance().line(logCall("ctor"))).isEqualTo(8);
    assertThat(ctx.source(type).line()).isEqualTo(3);
  }

  @Test
  void elementSansPosition() {
    assertThat(ctx.provenance().file(null)).isNull();
    assertThat(ctx.provenance().line(null)).isNull();
    assertThat(Provenance.className(null)).isNull();
  }

  @Test
  void cheminsRelatifs() {
    assertThat(ctx.provenance().relative(dir.resolve("demo/Service.java"))).isEqualTo("demo/Service.java");
    File dehors = dir.getParent().resolve("ailleurs.txt").toFile();
    assertThat(ctx.provenance().relative(dehors)).startsWith("/").endsWith("ailleurs.txt");
  }

  @Test
  void ordreParPositionDansLeFichier() {
    assertThat(Provenance.offset(method("appel"))).isLessThan(Provenance.offset(method("suivant")));
  }

  @Test
  void metadonneesKotlinPrioritaires() {
    CtType<?> copie = type.clone();
    copie.putMetadata(Provenance.META_LANG, Provenance.LANG_KOTLIN);
    CtField<?> champ = copie.getField("url");
    champ.putMetadata(Provenance.META_FILE, "src/main/kotlin/Service.kt");
    champ.putMetadata(Provenance.META_LINE, 40);
    assertThat(ctx.provenance().file(champ)).isEqualTo("src/main/kotlin/Service.kt");
    assertThat(ctx.provenance().line(champ)).isEqualTo(40);
    assertThat(Provenance.isKotlin(champ)).isTrue();
    assertThat(Provenance.isKotlin(type.getField("url"))).isFalse();
  }

  @Test
  void appelant() {
    assertThat(Callers.of(logCall("lambda"))).isEqualTo("demo.Service#appel");
    assertThat(Callers.of(logCall("ctor"))).isEqualTo("demo.Service#<init>");
    assertThat(Callers.of(method("suivant"))).isEqualTo("demo.Service#suivant");
    assertThat(Callers.of(type.getField("url").getDefaultExpression())).isNull();
  }
}
