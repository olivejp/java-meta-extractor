package fr.cafat.admin;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

@Path("sante")
public class SanteResource {

  @GET
  public String etat() {
    return "OK";
  }
}
