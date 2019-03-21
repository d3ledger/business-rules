package iroha.validation.rest;

import com.google.protobuf.InvalidProtocolBufferException;
import iroha.protocol.Queries.Query;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import jp.co.soramitsu.iroha.java.IrohaAPI;

@Singleton
@Path("")
public class RestService {

  @Inject
  private RegistrationProvider registrationProvider;
  @Inject
  private TransactionVerdictStorage verdictStorage;
  @Inject
  private IrohaAPI irohaAPI;

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
  public Response register(@PathParam("accountId") String accountId) {
    try {
      registrationProvider.register(accountId);
    } catch (Exception e) {
      return Response.status(422).entity(e).build();
    }
    return Response.status(204).build();
  }

  @POST
  @Path("/transaction")
  public Response sendTransactionSync(@QueryParam("async") @DefaultValue("false") String async,
      final byte[] transaction) {
    try {
      final Transaction tx = Transaction.parseFrom(transaction);
      if (Boolean.parseBoolean(async)) {
        irohaAPI.transactionSync(tx);
      } else {
        irohaAPI.transaction(tx);
      }
    } catch (Throwable t) {
      return Response.status(500).build();
    }
    return Response.status(204).build();
  }

  @POST
  @Path("/query")
  public Response query(final byte[] query) {
    try {
      return Response.status(200).entity(irohaAPI.query(Query.parseFrom(query)).toString()).build();
    } catch (InvalidProtocolBufferException e) {
      return Response.status(404).build();
    } catch (Throwable t) {
      return Response.status(500).build();
    }
  }
}
