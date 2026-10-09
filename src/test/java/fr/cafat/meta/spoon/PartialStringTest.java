package fr.cafat.meta.spoon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PartialStringTest {

  @Test
  void litteralVideDonneLaChaineVide() {
    assertThat(PartialString.lit(null)).isSameAs(PartialString.EMPTY);
    assertThat(PartialString.lit("")).isSameAs(PartialString.EMPTY);
    assertThat(PartialString.EMPTY.isEmpty()).isTrue();
    assertThat(PartialString.EMPTY.isComplete()).isTrue();
    assertThat(PartialString.EMPTY.render()).isEmpty();
  }

  @Test
  void concatFusionneLesLitterauxAdjacents() {
    PartialString s = PartialString.concat(PartialString.lit("http://"), PartialString.lit("h"),
        PartialString.unknown("id"), PartialString.lit("/"), PartialString.lit("x"));
    assertThat(s.parts()).containsExactly(new PartialString.Lit("http://h"), new PartialString.Unknown("id"),
        new PartialString.Lit("/x"));
    assertThat(s.render()).isEqualTo("http://h{id}/x");
    assertThat(s.literals()).containsExactly("http://h", "/x");
    assertThat(s.withUnknownsAs("*")).isEqualTo("http://h*/x");
    assertThat(s.isComplete()).isFalse();
    assertThat(s.valueOrNull()).isNull();
  }

  @Test
  void concatPropageDynamique() {
    PartialString dyn = PartialString.lit("a").markDynamic();
    assertThat(dyn.dynamic()).isTrue();
    assertThat(dyn.markDynamic()).isSameAs(dyn);
    assertThat(PartialString.concat(List.of(PartialString.lit("x"), dyn)).dynamic()).isTrue();
    assertThat(PartialString.lit("x").append(PartialString.lit("y")).dynamic()).isFalse();
    assertThat(PartialString.lit("x").append(PartialString.lit("y")).valueOrNull()).isEqualTo("xy");
  }

  @Test
  void morceauInconnu() {
    PartialString u = PartialString.unknown("service.host()");
    assertThat(u.render()).isEqualTo("{service.host}");
    assertThat(u.startsWithUnknown()).isTrue();
    assertThat(PartialString.unknown(null).render()).isEqualTo("{?}");
    assertThat(PartialString.unknown("é").render()).isEqualTo("{?}");
    assertThat(PartialString.unknown("x".repeat(50)).render()).hasSize(42);
    assertThat(PartialString.lit("a").append(u).startsWithUnknown()).isFalse();
    assertThat(PartialString.EMPTY.startsWithUnknown()).isFalse();
  }

  @Test
  void toStringEstLeRendu() {
    assertThat(PartialString.concat(PartialString.lit("a"), PartialString.unknown("b")).toString())
        .isEqualTo("a{b}");
  }
}
