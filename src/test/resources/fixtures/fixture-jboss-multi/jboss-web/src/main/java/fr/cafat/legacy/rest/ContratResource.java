package fr.cafat.legacy.rest;

import fr.cafat.legacy.domain.Contrat;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/contrats")
@Produces(MediaType.APPLICATION_JSON)
public class ContratResource {

  @GET
  @Path("/{id: [0-9]+}")
  public Contrat get(@PathParam("id") Long id) {
    return null;
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response creer(Contrat contrat) {
    return Response.ok().build();
  }

  @Path("/{id}/assure")
  @GET
  public String assure(@PathParam("id") String id) {
    String base = System.getProperty("assure.url");
    return ClientBuilder.newClient().target(base).path("assures").path(id)
        .request(MediaType.APPLICATION_JSON).get(String.class);
  }
}
