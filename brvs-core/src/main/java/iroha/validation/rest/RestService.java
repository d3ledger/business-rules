/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.Printer;
import io.reactivex.schedulers.Schedulers;
import iroha.protocol.Queries.Query;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.protocol.TransactionOuterClass.Transaction.Builder;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import java.security.KeyPair;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Utils;

@Singleton
@Path("")
public class RestService {

  private final static Printer printer = JsonFormat.printer()
      .omittingInsignificantWhitespace()
      .preservingProtoFieldNames();
  private final static Parser parser = JsonFormat.parser().ignoringUnknownFields();

  @Inject
  private RegistrationProvider registrationProvider;
  @Inject
  private TransactionVerdictStorage verdictStorage;
  @Inject
  private IrohaAPI irohaAPI;
  @Inject
  private CacheProvider cacheProvider;
  @Inject
  private KeyPair brvsAccountKeyPair;

  @GET
  @Path("/status/{txHash}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getStatus(@PathParam("txHash") String hash) {
    ValidationResult transactionVerdict = verdictStorage.getTransactionVerdict(hash);
    if (transactionVerdict == null) {
      transactionVerdict = ValidationResult.UNKNOWN;
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
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransaction(String transaction) {
    return sendTransaction(transaction, false);
  }

  @POST
  @Path("/transaction/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionWithSigning(String transaction) {
    return sendTransaction(transaction, true);
  }

  private Response sendTransaction(String transaction, boolean sign) {
    try {
      final Builder builder = Transaction.newBuilder();
      parser.merge(transaction, builder);
      // since final or effectively final is needed
      final Transaction builtTx = builder.build();
      final Transaction transactionsToSend;
      if (sign) {
        transactionsToSend = jp.co.soramitsu.iroha.java.Transaction.parseFrom(builtTx)
            .sign(brvsAccountKeyPair).build();
      } else {
        transactionsToSend = builtTx;
      }
      StreamingOutput streamingOutput = output -> irohaAPI.transaction(transactionsToSend)
          .subscribeOn(Schedulers.from(Executors.newSingleThreadExecutor()))
          .blockingSubscribe(toriiResponse -> {
                output.write(printer.print(toriiResponse).getBytes());
                output.flush();
              }
          );
      return Response.status(200).entity(streamingOutput).build();
    } catch (InvalidProtocolBufferException e) {
      return Response.status(422).build();
    } catch (Throwable t) {
      return Response.status(500).build();
    }
  }

  @GET
  @Path("/transactions")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getTransactions() throws InvalidProtocolBufferException {
    return Response.status(200)
        .entity(printer.print(Utils.createTxList(cacheProvider.getTransactions()))).build();
  }

  @POST
  @Path("/query")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response query(String query) {
    try {
      final Query.Builder builder = Query.newBuilder();
      parser.merge(query, builder);
      return Response.status(200).entity(printer.print(irohaAPI.query(builder.build()))).build();
    } catch (InvalidProtocolBufferException e) {
      return Response.status(422).build();
    } catch (Throwable t) {
      return Response.status(500).build();
    }
  }
}
