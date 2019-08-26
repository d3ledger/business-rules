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
import org.apache.http.HttpStatus;
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
    return Response.status(HttpStatus.SC_OK).entity(transactionVerdict).build();
  }

  @POST
  @Path("/register/{accountId}")
  public Response register(@PathParam("accountId") String accountId) {
    try {
      registrationProvider.register(accountId);
    } catch (Exception e) {
      return Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity(e).build();
    }
    return Response.status(HttpStatus.SC_NO_CONTENT).build();
  }

  @POST
  @Path("/transaction/send")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionNoSign(String transaction) {
    return sendTransactionWithoutSigning(transaction);
  }

  @POST
  @Path("/transaction/send/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionSign(String transaction) {
    return sendTransactionWithSigning(transaction);
  }

  private Response sendTransactionWithSigning(String transaction) {
    return sendTransaction(transaction, true);
  }

  private Response sendTransactionWithoutSigning(String transaction) {
    return sendTransaction(transaction, false);
  }

  /**
   * Parses Iroha serialized transaction to {@link iroha.protocol.TransactionOuterClass.TransactionOrBuilder}
   * and performs gRPC call to send transaction. If sign parameter is set to true the transaction is
   * additionally signed with brvs key.
   *
   * @param transaction JSONed proto transaction
   * @param sign sign flag
   * @return {@link Response HTTP} {@link HttpStatus 200} with transaction status stream <br> {@link
   * Response HTTP} {@link HttpStatus 422} if JSON supplied is incorrect or quorum is not satisfied
   * before sending
   * <br> {@link Response HTTP} {@link HttpStatus 500} if any other error occurred
   */
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
      return Response.status(HttpStatus.SC_OK).entity(streamingOutput).build();
    } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
      logger.error("Error during transaction processing", e);
      return Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).build();
    } catch (Exception e) {
      logger.error("Error during transaction processing", e);
      return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
    }
  }

  @GET
  @Path("/transactions")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getTransactions() throws InvalidProtocolBufferException {
    return Response.status(HttpStatus.SC_OK)
        .entity(printer.print(Utils.createTxList(cacheProvider.getTransactions()))).build();
  }

  @POST
  @Path("/query/send")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendQueryNoSign(String query) {
    return executeQueryWithoutSigning(query);
  }

  @POST
  @Path("/query/send/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response signQueryWithSign(String query) {
    return executeQueryWithSigning(query);
  }

  private Response executeQueryWithSigning(String query) {
    return sendQuery(query, true);
  }

  private Response executeQueryWithoutSigning(String query) {
    return sendQuery(query, false);
  }

  /**
   * Parses Iroha serialized query to {@link iroha.protocol.Queries.Query} and performs gRPC call to
   * execute query. If sign parameter is set to true the query is additionally signed with brvs
   * key.
   *
   * @param query JSONed proto query
   * @param sign sign flag
   * @return {@link Response HTTP} {@link HttpStatus 200} with query execution result <br> {@link
   * Response HTTP} {@link HttpStatus 422} if JSON supplied is incorrect or there is no signature
   * before sending
   * <br> {@link Response HTTP} {@link HttpStatus 500} if any other error occurred
   */
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
        throw new IllegalArgumentException(msg);
      }
      return Response.status(HttpStatus.SC_OK).entity(printer.print(irohaAPI.query(queryToSend)))
          .build();
    } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
      logger.error("Error during query processing", e);
      return Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).build();
    } catch (Exception e) {
      logger.error("Error during query processing", e);
      return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
    }
  }

  @POST
  @Path("/batch/send")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionsBatchNoSign(String transactionList) {
    return sendTransactionsBatchWithoutSigning(transactionList);
  }

  @POST
  @Path("/batch/send/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionsBatchWithSign(String transactionList) {
    return sendTransactionsBatchWithSigning(transactionList);
  }

  private Response sendTransactionsBatchWithSigning(String transactionList) {
    return sendTransactionsBatch(transactionList, true);
  }

  private Response sendTransactionsBatchWithoutSigning(String transactionList) {
    return sendTransactionsBatch(transactionList, false);
  }

  /**
   * Parses Iroha serialized transaction batch to {@link iroha.protocol.Endpoint.TxList} and
   * performs gRPC call to send it. If sign parameter is set to true the batch is additionally
   * signed with brvs key if there is a signature slot for that.
   *
   * @param transactionList JSONed proto TxList
   * @param sign sign flag
   * @return {@link Response HTTP} {@link HttpStatus 200} with first transaction status stream <br>
   * {@link Response HTTP} {@link HttpStatus 422} if JSON supplied is incorrect or quorum is not
   * satisfied before sending
   * <br> {@link Response HTTP} {@link HttpStatus 500} if any other error occurred
   */
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
      return Response.status(HttpStatus.SC_OK).entity(streamingOutput).build();
    } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
      logger.error("Error during batch processing", e);
      return Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).build();
    } catch (Exception e) {
      logger.error("Error during batch processing", e);
      return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
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
      throw new IllegalArgumentException(msg);
    }
  }
}
