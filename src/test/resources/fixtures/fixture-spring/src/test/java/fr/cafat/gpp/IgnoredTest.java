package fr.cafat.gpp;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Code de test : ne doit jamais apparaître dans la sortie. */
public class IgnoredTest {

  @Entity
  public static class TestOnlyEntity {
    @Id
    private Long id;
  }

  @RestController
  public static class TestOnlyController {
    @GetMapping("/test-only")
    public String get() {
      return "";
    }
  }
}
