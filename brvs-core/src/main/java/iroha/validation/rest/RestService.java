/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest;

import static iroha.validation.utils.ValidationUtils.subscriptionStrategy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.Printer;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import iroha.protocol.Endpoint.TxList;
import iroha.protocol.Queries.Query;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.protocol.TransactionOuterClass.Transaction.Builder;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Path("")
public class RestService {

  private final static Logger logger = LoggerFactory.getLogger(RestService.class);
  private final static Printer printer = JsonFormat.printer()
      .omittingInsignificantWhitespace()
      .preservingProtoFieldNames();
  private final static Parser parser = JsonFormat.parser().ignoringUnknownFields();
  private final Scheduler scheduler = Schedulers.from(Executors.newCachedThreadPool());

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
      final String hash = Utils.toHexHash(builtTx);
      if (sign) {
        logger.info("Going to sign transaction:" + hash);
        transactionsToSend = jp.co.soramitsu.iroha.java.Transaction.parseFrom(builtTx)
            .sign(brvsAccountKeyPair).build();
      } else {
        logger.info("Not going to sign transaction: " + hash);
        transactionsToSend = builtTx;
      }
      checkTransactionSignaturesCount(transactionsToSend);
      logger.info("Going to send transaction: " + hash);
      StreamingOutput streamingOutput = output -> irohaAPI
          .transaction(transactionsToSend, subscriptionStrategy)
          .subscribeOn(scheduler)
          .blockingSubscribe(toriiResponse -> {
                output.write(printer.print(toriiResponse).getBytes());
                output.flush();
              }
          );
      return Response.status(200).entity(streamingOutput).build();
    } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
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
  public Response sendQuery(String query) {
    return sendQuery(query, false);
  }

  @POST
  @Path("/query/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response signQueryWithSigning(String query) {
    return sendQuery(query, true);
  }

  private Response sendQuery(String query, boolean sign) {
    try {
      final Query.Builder builder = Query.newBuilder();
      parser.merge(query, builder);
      Query queryToSend = builder.build();
      if (sign) {
        queryToSend = new jp.co.soramitsu.iroha.java.Query(queryToSend)
            .buildSigned(brvsAccountKeyPair);
      }
      if (!queryToSend.hasSignature()) {
        final String msg = "Query does not have signature";
        logger.error(msg);
        throw new IllegalArgumentException(msg);
      }
      return Response.status(200).entity(printer.print(irohaAPI.query(queryToSend))).build();
    } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
      return Response.status(422).build();
    } catch (Throwable t) {
      return Response.status(500).build();
    }
  }

  @POST
  @Path("/batch")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionsBatch(String transactionList) {
    return sendTransactionsBatch(transactionList, false);
  }

  @POST
  @Path("/batch/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionsBatchWithSigning(String transactionList) {
    return sendTransactionsBatch(transactionList, true);
  }

  private Response sendTransactionsBatch(String transactionList, boolean sign) {
    try {
      final TxList.Builder builder = TxList.newBuilder();
      parser.merge(transactionList, builder);
      final TxList builtTx = builder.build();
      final List<Transaction> transactionsToSend;
      final String batchHashes = builtTx.getTransactionsList().stream().map(Utils::toHexHash)
          .collect(Collectors.joining(","));
      if (sign) {
        logger.info("Going to sign transaction batch: " + batchHashes);
        transactionsToSend = builtTx.getTransactionsList().stream()
            .map(transaction -> {
              final int signaturesCount = transaction.getSignaturesCount();
              final int quorum = transaction.getPayload().getReducedPayload().getQuorum();
              if (signaturesCount < quorum) {
                return jp.co.soramitsu.iroha.java.Transaction.parseFrom(transaction)
                    .sign(brvsAccountKeyPair)
                    .build();
              } else {
                return transaction;
              }
            }).collect(Collectors.toList());
      } else {
        logger.info("Not going to sign transaction batch: " + batchHashes);
        transactionsToSend = builtTx.getTransactionsList().stream().map(
            transaction -> jp.co.soramitsu.iroha.java.Transaction.parseFrom(transaction).build())
            .collect(Collectors.toList());
      }
      transactionsToSend.forEach(this::checkTransactionSignaturesCount);
      logger.info("Going to send transaction batch: " + batchHashes);
      irohaAPI.transactionListSync(transactionsToSend);
      StreamingOutput streamingOutput = output -> subscriptionStrategy
          .subscribe(irohaAPI, Utils.hash(transactionsToSend.get(0)))
          .subscribeOn(scheduler)
          .blockingSubscribe(toriiResponse -> {
                output.write(printer.print(toriiResponse).getBytes());
                output.flush();
              }
          );
      return Response.status(200).entity(streamingOutput).build();
    } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
      return Response.status(422).build();
    } catch (Throwable t) {
      return Response.status(500).build();
    }
  }

  private void checkTransactionSignaturesCount(Transaction transaction) {
    final int signaturesCount = transaction.getSignaturesCount();
    final int quorum = transaction.getPayload().getReducedPayload().getQuorum();
    if (signaturesCount < quorum) {
      final String msg =
          "Transaction " + Utils.toHexHash(transaction)
              + " does not have enough signatures: Quorum: " + quorum
              + " Signatures: " + signaturesCount;
      logger.error(msg);
      throw new IllegalArgumentException(msg);
    }
  }
}
