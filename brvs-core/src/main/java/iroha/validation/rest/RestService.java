package iroha.validation.rest;

import iroha.validation.service.ValidationService;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import org.springframework.util.StringUtils;

@Singleton
@Path("")
public class RestService {

  @Inject
  private ValidationService validationService;
  @Inject
  private TransactionVerdictStorage verdictStorage;
  @Inject
  private AccountValidityChecker accountValidityChecker;

  @GET
  @Path("/status/{txHash}")
  public Response getStatus(@PathParam("txHash") String hash) {
    ValidationResult transactionVerdict = verdictStorage.getTransactionVerdict(hash);
    if (transactionVerdict == null) {
      return Response.status(404).entity("Transaction hash is not known.").build();
    }
    return Response.status(200).entity(transactionVerdict).build();
  }

  @POST
  @Path("/register/{accountId}")
  public Response register(@PathParam("accountId") String accountId) {
    if (!hasValidaFormat(accountId)) {
      return Response.status(422).entity("Invalid account format. Use 'username@domain'.").build();
    }
    if (!accountValidityChecker.existsInIroha(accountId)) {
      return Response.status(404).entity("Account does not exist.").build();
    }
    validationService.registerAccount(accountId);
    return Response.status(204).build();
  }

  private boolean hasValidaFormat(String accountId) {
    return !StringUtils.isEmpty(accountId) && accountId.split("@").length == 2;
  }
}
