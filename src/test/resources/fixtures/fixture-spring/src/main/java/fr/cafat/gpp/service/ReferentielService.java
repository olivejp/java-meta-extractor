package fr.cafat.gpp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class ReferentielService {

  private static final String COMMUNES = "/communes/{code}";

  private final RestTemplate restTemplate;
  private final WebClient batchClient = WebClient.create("http://s-gen-batch:8080");
  private final RestClient restClient = RestClient.create();

  @Value("${geo.url}")
  private String geoUrl;

  public ReferentielService(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  public String commune(String code) {
    return restTemplate.getForObject(geoUrl + COMMUNES, String.class, code);
  }

  public void majPartenaire(String payload) {
    String base = System.getenv("PARTNER_URL");
    restTemplate.exchange(base + "/partenaires", HttpMethod.PUT, null, Void.class);
  }

  public void lancerJob(String nom) {
    batchClient.post().uri("/jobs/{nom}", nom).retrieve().toBodilessEntity().block();
  }

  public String statutExterne() {
    return restClient.get().uri("https://api.partenaire-externe.com/v2/status").retrieve().body(String.class);
  }
}
