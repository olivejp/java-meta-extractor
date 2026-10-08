package fr.cafat.meta.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceFilteringTest {

  private static final Path RESOURCES = Path.of("/repo/src/main/resources");
  private static final List<String> BOOT = List.of("**/application*.yml", "**/application*.properties");

  @Test
  void motifsAnt() {
    assertThat(ResourceFiltering.ant("**/application*.yml", "application.yml")).isTrue();
    assertThat(ResourceFiltering.ant("**/application*.yml", "config/application-dev.yml")).isTrue();
    assertThat(ResourceFiltering.ant("*.yml", "config/application.yml")).isFalse();
    assertThat(ResourceFiltering.ant("config/", "config/a/b.xml")).isTrue();
    assertThat(ResourceFiltering.ant("a?c.txt", "abc.txt")).isTrue();
  }

  @Test
  void jetonsConnusSeulement() {
    ResourceFiltering boot = new ResourceFiltering(Map.of("artifactId", "s-gen-demo", "v", "1"), false,
        List.of(new ResourceFiltering.Resource(RESOURCES, true, BOOT, List.of()),
            new ResourceFiltering.Resource(RESOURCES, false, List.of(), BOOT)));
    Path yml = RESOURCES.resolve("application.yml");
    // jeton inconnu (au milieu d'une valeur) laissé tel quel, ${…} laissé à Spring sous le parent Boot
    assertThat(boot.apply(yml, "a: @artifactId@\nb: x@inconnu@y\nc: ${v}"))
        .isEqualTo("a: s-gen-demo\nb: x@inconnu@y\nc: ${v}");
    // fichier non couvert par une ressource filtrée
    assertThat(boot.apply(RESOURCES.resolve("logback.xml"), "@artifactId@")).isEqualTo("@artifactId@");
    assertThat(boot.apply(Path.of("/ailleurs/application.yml"), "@artifactId@")).isEqualTo("@artifactId@");

    ResourceFiltering maven = new ResourceFiltering(Map.of("v", "1"), true,
        List.of(new ResourceFiltering.Resource(RESOURCES, true, List.of(), List.of())));
    assertThat(maven.apply(yml, "a: ${v}/@v@/${spring.x}")).isEqualTo("a: 1/1/${spring.x}");
    assertThat(ResourceFiltering.NONE.apply(yml, "@v@")).isEqualTo("@v@");
  }
}
