package fr.cafat.gpp.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Client résolu par découverte de services (pas d'URL). */
@FeignClient("s-gen-notif")
public interface NotifClient {

  @PostMapping("/notifications")
  void envoyer(@RequestBody String message);
}
