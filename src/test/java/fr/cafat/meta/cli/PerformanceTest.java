package fr.cafat.meta.cli;

import static org.assertj.core.api.Assertions.assertThat;

import fr.cafat.meta.model.ExtractionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Critère d'acceptation : un dépôt de plus de 100 classes s'extrait en moins de 60 secondes. */
class PerformanceTest {

  static final int N = 30;

  @Test
  void centVingtClassesEnMoinsDeSoixanteSecondes(@TempDir Path repo) throws IOException {
    write(repo, "pom.xml", """
        <project><modelVersion>4.0.0</modelVersion><groupId>fr.cafat</groupId><artifactId>s-perf</artifactId>
        <version>1.0.0</version><build><plugins><plugin><groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>
        """);
    write(repo, "src/main/resources/application.yml", """
        spring:
          application:
            name: s-perf
          datasource:
            url: jdbc:postgresql://localhost/perf?currentSchema=perf
        partenaire:
          url: http://s-partenaire/api
        """);
    String base = "src/main/java/fr/cafat/perf/";
    write(repo, base + "PerfApplication.java", """
        package fr.cafat.perf;
        @org.springframework.boot.autoconfigure.SpringBootApplication
        public class PerfApplication { }
        """);
    for (int i = 0; i < N; i++) {
      int next = (i + 1) % N;
      write(repo, base + "domain/Objet" + i + ".java", """
          package fr.cafat.perf.domain;
          import javax.persistence.*;
          @Entity
          @Table(name = "objet_%1$d")
          public class Objet%1$d {
            @Id @GeneratedValue private Long id;
            @Column(name = "libelle", length = 80, nullable = false) private String libelle;
            private java.time.LocalDate dateCreation;
            @ManyToOne @JoinColumn(name = "suivant_id") private Objet%2$d suivant;
            @OneToMany(mappedBy = "parent") private java.util.List<Objet%2$d> enfants;
            @ManyToOne private Objet%2$d parent;
          }
          """.formatted(i, next));
      write(repo, base + "dao/Dao" + i + ".java", """
          package fr.cafat.perf.dao;
          import org.springframework.jdbc.core.JdbcTemplate;
          @org.springframework.stereotype.Repository
          public class Dao%1$d {
            private final JdbcTemplate jdbcTemplate;
            public Dao%1$d(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }
            public java.util.List<java.util.Map<String, Object>> lire(long id) {
              return jdbcTemplate.queryForList("SELECT o.libelle FROM objet_%1$d o JOIN objet_%2$d s ON s.id = o.suivant_id WHERE o.id = ?", id);
            }
            public int purger() { return jdbcTemplate.update("DELETE FROM objet_%1$d WHERE libelle IS NULL"); }
          }
          """.formatted(i, next));
      write(repo, base + "web/Controleur" + i + ".java", """
          package fr.cafat.perf.web;
          import org.springframework.web.bind.annotation.*;
          @RestController
          @RequestMapping("/api/objets%1$d")
          public class Controleur%1$d {
            @GetMapping("/{id}") public fr.cafat.perf.domain.Objet%1$d lire(@PathVariable Long id) { return null; }
            @PostMapping public void creer(@RequestBody fr.cafat.perf.domain.Objet%1$d o) { }
            @DeleteMapping("/{id}") public void supprimer(@PathVariable("id") Long id) { }
          }
          """.formatted(i));
      write(repo, base + "client/Client" + i + ".java", """
          package fr.cafat.perf.client;
          import org.springframework.cloud.openfeign.FeignClient;
          import org.springframework.web.bind.annotation.*;
          @FeignClient(name = "partenaire%1$d", url = "${partenaire.url}")
          public interface Client%1$d {
            @GetMapping("/elements/{id}") String element(@PathVariable("id") String id);
          }
          """.formatted(i));
    }

    long start = System.nanoTime();
    List<Pipeline.Output> outputs = Pipeline.run(repo,
        new Pipeline.Options("abc1234", null, List.of(), Set.of("MGENGPP"), Main.KOTLIN));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(outputs).hasSize(1);
    ExtractionResult r = outputs.get(0).result();
    assertThat(r.application().id()).isEqualTo("s-perf");
    assertThat(r.entities()).hasSize(N);
    assertThat(r.relations()).hasSize(3 * N);
    assertThat(r.sqlAccesses()).hasSize(2 * N);
    assertThat(r.endpoints()).hasSize(3 * N);
    assertThat(r.calls()).hasSize(N);
    assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
    System.err.println("PerformanceTest : " + (4 * N + 1) + " classes extraites en " + elapsed.toMillis() + " ms");
  }

  private static void write(Path root, String rel, String content) throws IOException {
    Path f = root.resolve(rel);
    Files.createDirectories(f.getParent());
    Files.writeString(f, content, StandardCharsets.UTF_8);
  }
}
