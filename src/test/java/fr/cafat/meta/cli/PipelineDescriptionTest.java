package fr.cafat.meta.cli;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.scan.Module;
import fr.cafat.meta.scan.ResourceFiltering;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineDescriptionTest {

  private static Module module(String description) {
    return new Module("nc.cafat:app", "app", "1.0", description, Path.of("."), "", "jar", null, List.of(),
        List.of(), false, "pom.xml", ResourceFiltering.NONE);
  }

  @Test
  void espacesNormalises() {
    assertThat(Pipeline.description(module("  Services sur la personne\n\t physique ")))
        .isEqualTo("Services sur la personne physique");
  }

  @Test
  void absenteNonResolueOuParDefaut() {
    assertThat(Pipeline.description(module(null))).isNull();
    assertThat(Pipeline.description(module("Service ${inconnue}"))).isNull();
    assertThat(Pipeline.description(module("Demo project for Spring Boot"))).isNull();
  }
}
