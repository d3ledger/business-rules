package iroha.validation.rest;

import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.springframework.util.StringUtils;

@Singleton
@Path("")
public class RestService {

  @Inject
  private RegistrationProvider registrationProvider;
  @Inject
  private TransactionVerdictStorage verdictStorage;

  @GET
  @Path("/status/{txHash}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getStatus(@PathParam("txHash") String hash) {
    ValidationResult transactionVerdict = verdictStorage.getTransactionVerdict(hash);
    if (transactionVerdict == null) {
      return Response.status(404).entity("Transaction hash is not known.").build();
    }
    return Response.status(200).entity(transactionVerdict).build();
  }

  @POST
  @Path("/register/{accountId}")
  public Response register(@PathParam("accountId") String accountId,
      @QueryParam("time") String time) {
    try {
      long syncTime;
      if (StringUtils.isEmpty(time)) {
        final long currentTimeMillis = System.currentTimeMillis();
        syncTime = currentTimeMillis - currentTimeMillis % 10000000;
      } else {
        syncTime = Long.parseLong(time);
      }
      registrationProvider.register(accountId, syncTime);
    } catch (Exception e) {
      return Response.status(422).entity(e).build();
    }
    return Response.status(204).build();
  }
}
