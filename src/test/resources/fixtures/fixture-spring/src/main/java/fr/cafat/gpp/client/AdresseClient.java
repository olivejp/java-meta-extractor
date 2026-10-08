package fr.cafat.gpp.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "s-ref-adresse", url = "${referentiel.url}", path = "/v1")
public interface AdresseClient {

  @GetMapping("/adresses/{id}")
  String getAdresse(@PathVariable("id") Long id);

  @PostMapping("/adresses")
  String creerAdresse(@RequestBody String adresse);
}
