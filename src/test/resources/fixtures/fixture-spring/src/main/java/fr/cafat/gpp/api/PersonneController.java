package fr.cafat.gpp.api;

import fr.cafat.gpp.api.dto.PersonneDto;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/personnes")
public class PersonneController {

  @GetMapping("/{id}")
  public PersonneDto getPersonne(@PathVariable Long id) {
    return null;
  }

  @PostMapping
  public ResponseEntity<PersonneDto> creer(@RequestBody PersonneDto dto) {
    return ResponseEntity.ok(dto);
  }

  @RequestMapping(value = "/search", method = RequestMethod.GET)
  public List<PersonneDto> rechercher(@RequestParam String nom) {
    return List.of();
  }

  @DeleteMapping("/{id:\\d+}")
  public void supprimer(@PathVariable Long id) {
  }

  @RequestMapping(path = {"/export", "/export.csv"}, method = {RequestMethod.GET, RequestMethod.HEAD})
  public byte[] exporter() {
    return new byte[0];
  }

  private void utilitaire() {
  }
}
