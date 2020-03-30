/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest;

import static iroha.validation.exception.BrvsErrorCode.MALFORMED_QUERY;
import static iroha.validation.exception.BrvsErrorCode.MALFORMED_TRANSACTION;
import static iroha.validation.exception.BrvsErrorCode.REGISTRATION_FAILED;
import static iroha.validation.utils.ValidationUtils.derivePublicKey;
import static iroha.validation.utils.ValidationUtils.fieldValidator;
import static iroha.validation.utils.ValidationUtils.subscriptionStrategy;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.Printer;
import iroha.protocol.Endpoint.ToriiResponse;
import iroha.protocol.Endpoint.TxList;
import iroha.protocol.Queries.Query;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.protocol.TransactionOuterClass.Transaction.Builder;
import iroha.validation.exception.BrvsException;
import iroha.validation.rest.dto.BinaryTransaction;
import iroha.validation.rest.dto.GenericStatusedResponse;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.verdict.ValidationResult;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.List;
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
import jp.co.soramitsu.crypto.ed25519.EdDSAPrivateKey;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.java.detail.BuildableAndSignable;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

@Singleton
@Path("/v1")
public class BusinessController {

  /**
   * Lambda function interface with unhandled exception
   *
   * @param <T> input parameter type
   * @param <R> return type
   */
  @FunctionalInterface
  public interface CheckedFunction<T, R> {

    R apply(T t) throws InvalidProtocolBufferException;
  }

  private static final Logger logger = LoggerFactory.getLogger(BusinessController.class);
  private static final Printer printer = JsonFormat.printer()
      .omittingInsignificantWhitespace()
      .preservingProtoFieldNames();
  private static final Parser parser = JsonFormat.parser().ignoringUnknownFields();

  @Inject
  private RegistrationProvider registrationProvider;
  @Inject
  private TransactionVerdictStorage verdictStorage;
  @Inject
  private IrohaAPI irohaAPI;

  /**
   * Keypair used to sign incoming transactions
   */
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
    return Response.ok(new ValidationResultResponse(transactionVerdict)).build();
  }

  @POST
  @Path("/register/{accountId}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response register(@PathParam("accountId") String accountId) {
    try {
      registrationProvider.register(accountId);
    } catch (InterruptedException e) {
      throw new BrvsException(e.getMessage(), e, REGISTRATION_FAILED);
    }
    return Response.ok(GenericStatusedResponse.SUCCESS).build();
  }

  @POST
  @Path("/isRegistered")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response isRegistered(String jsonBody) {
    final String accountId = ValidationUtils.gson
        .fromJson(jsonBody, AccountIdJsonWrapper.class)
        .getAccountId();
    fieldValidator.checkAccountId(accountId);
    final boolean isRegistered = registrationProvider.getRegisteredAccounts()
        .stream()
        .anyMatch(registeredAccount -> registeredAccount.equals(accountId));
    return Response.ok(new AccountRegisteredResponse(isRegistered)).build();
  }

  @POST
  @Path("/transaction/send")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionNoSign(String transaction) {
    return buildResponse(transaction, tx -> {
      final Transaction builtTx = buildTransaction(tx);
      return sendBuiltTransaction(builtTx);
    });
  }

  @POST
  @Path("/transaction/send/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionSign(String transaction) {
    return buildResponse(transaction, tx -> {
      final Transaction builtTx = buildTransaction(tx);
      final Transaction signedTx = signTransaction(builtTx);
      return sendBuiltTransaction(signedTx);
    });
  }

  @POST
  @Path("/transaction/send/signCustom")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionSignCustom(String jsonBody) {
    final TransactionWithSignatoriesJsonWrapper transactionWrapper = ValidationUtils.gson
        .fromJson(jsonBody, TransactionWithSignatoriesJsonWrapper.class);
    return buildResponse(transactionWrapper, tx -> {
      final Transaction builtTx = buildTransaction(tx.getTransaction());
      final Transaction signedTx = signTransactionWithCustomKeys(
          builtTx,
          transactionWrapper.getKeys()
      );
      return sendBuiltTransaction(signedTx);
    });
  }

  @POST
  @Path("/transaction/sendBinary")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionBinaryNoSign(String jsonBinaryTx) {
    return buildResponse(jsonBinaryTx, tx -> {
      byte[] bytes = decode(jsonBinaryTx);
      final Transaction builtTx = buildTransaction(bytes);
      return sendBuiltTransaction(builtTx);
    });
  }

  @POST
  @Path("/transaction/sendBinary/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionBinarySign(String jsonBinaryTx) {
    return buildResponse(jsonBinaryTx, tx -> {
      byte[] bytes = decode(jsonBinaryTx);
      final Transaction builtTx = buildTransaction(bytes);
      final Transaction signedTx = signTransaction(builtTx);
      return sendBuiltTransaction(signedTx);
    });
  }

  /**
   * Parses Iroha serialized transaction to {@link iroha.protocol.TransactionOuterClass.Transaction}
   *
   * @param transaction - JSONed proto transaction
   * @return built protobuf transaction
   * @throws com.google.protobuf.InvalidProtocolBufferException on invalid format
   */
  private Transaction buildTransaction(String transaction)
      throws com.google.protobuf.InvalidProtocolBufferException {
    final Builder builder = Transaction.newBuilder();
    parser.merge(transaction, builder);
    return builder.build();
  }

  /**
   * Parses Iroha serialized transaction to {@link iroha.protocol.TransactionOuterClass.Transaction}
   *
   * @param transaction - byte array representation of protobuf transaction
   * @return built protobuf transaction
   * @throws com.google.protobuf.InvalidProtocolBufferException on invalid format
   */
  private Transaction buildTransaction(byte[] transaction)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return jp.co.soramitsu.iroha.java.Transaction
        .parseFrom(transaction)
        .build();
  }

  /**
   * Sign transaction with brvs key
   *
   * @param builtTx - protobuf transaction
   * @return signed protobuf Transaction
   */
  private Transaction signTransaction(Transaction builtTx) {
    final String hash = Utils.toHexHash(builtTx);
    logger.info("Going to sign transaction: {}", hash);
    return jp.co.soramitsu.iroha.java.Transaction.parseFrom(builtTx)
        .sign(brvsAccountKeyPair)
        .build();
  }

  /**
   * Sign transaction with custom key pairs
   *
   * @param builtTx - protoobuf transaction
   * @param privateKeys - custom hex keys list
   * @return signed protobuf Transaction
   */
  private Transaction signTransactionWithCustomKeys(Transaction builtTx,
      List<String> privateKeys) {
    final String hash = Utils.toHexHash(builtTx);
    logger.info("Going to sign transaction: {} with custom key pairs", hash);
    final BuildableAndSignable<Transaction> transaction = jp.co.soramitsu.iroha.java.Transaction
        .parseFrom(builtTx);
    privateKeys.forEach(key -> {
      final PrivateKey privateKey = Utils.parseHexPrivateKey(key);
      final KeyPair keyPair = new KeyPair(
          derivePublicKey((EdDSAPrivateKey) privateKey),
          privateKey
      );
      transaction.sign(keyPair);
    });
    return transaction.build();
  }

  /**
   * Performs gRPC call to send transaction.
   *
   * @param transaction - proto transaction
   * @return ToriiResponse with transaction status stream
   */
  private ToriiResponse sendBuiltTransaction(Transaction transaction) {
    final String hash = Utils.toHexHash(transaction);
    checkTransactionSignaturesCount(transaction);
    logger.info("Going to send transaction: {}", hash);
    return irohaAPI.transaction(transaction, subscriptionStrategy)
        .blockingLast();
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
      throw new BrvsException(e.getMessage(), e, MALFORMED_QUERY);
    }
  }

  @POST
  @Path("/batch/send")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionsBatchNoSign(String transactionList) {
    return buildResponse(transactionList, tx -> {
      List<Transaction> builtTransactions = buildBatch(tx);
      return sendBuiltBatch(builtTransactions);
    });
  }

  @POST
  @Path("/batch/send/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendTransactionsBatchWithSign(String transactionList) {
    return buildResponse(transactionList, tx -> {
      List<Transaction> builtTransactions = buildBatch(tx);
      List<Transaction> signedTransactions = signBatch(builtTransactions);
      return sendBuiltBatch(signedTransactions);
    });
  }

  @POST
  @Path("/batch/sendBinary")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendBatchBinaryNoSign(String jsonBinaryList) {
    return buildResponse(jsonBinaryList, tx -> {
      byte[] bytes = decode(jsonBinaryList);
      List<Transaction> builtTransactions = buildBatch(bytes);
      return sendBuiltBatch(builtTransactions);
    });
  }

  @POST
  @Path("/batch/sendBinary/sign")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response sendBatchBinarySign(String jsonBinaryList) {
    return buildResponse(jsonBinaryList, tx -> {
      byte[] bytes = decode(jsonBinaryList);
      List<Transaction> builtTransactions = buildBatch(bytes);
      List<Transaction> signedTransactions = signBatch(builtTransactions);
      return sendBuiltBatch(signedTransactions);
    });
  }

  /**
   * Decode hex string to bytes proto transactions
   *
   * @param hexString hex string representation
   * @return bytes of proto transaction
   */
  private byte[] decode(String hexString) {
    BinaryTransaction bt = ValidationUtils.gson.fromJson(hexString, BinaryTransaction.class);
    return Hex.decode(bt.hexString);
  }

  /**
   * Parses Iroha serialized transaction list to {@link iroha.protocol.Endpoint.TxList}
   *
   * @param transactionList - JSONed proto transaction list
   * @return built protobuf transaction list
   * @throws com.google.protobuf.InvalidProtocolBufferException on invalid format
   */
  private List<Transaction> buildBatch(String transactionList)
      throws com.google.protobuf.InvalidProtocolBufferException {
    final TxList.Builder builder = TxList.newBuilder();
    parser.merge(transactionList, builder);
    return builder.build().getTransactionsList();
  }

  /**
   * Parses Iroha serialized transaction to {@link iroha.protocol.Endpoint.TxList}
   *
   * @param transactionList - byte array representation of protobuf transaction list
   * @return list of built protobuf transaction
   * @throws com.google.protobuf.InvalidProtocolBufferException on invalid format
   */
  private List<Transaction> buildBatch(byte[] transactionList)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return TxList.parseFrom(transactionList)
        .toBuilder()
        .build()
        .getTransactionsList();
  }

  /**
   * Sign batch transactions with brvs key if there is a signature slot for that
   *
   * @param txList - protobuf transaction list
   * @return signed protobuf transaction list
   */
  private List<Transaction> signBatch(List<Transaction> txList) {
    final String batchHashes = txList.stream().map(Utils::toHexHash)
        .collect(Collectors.joining(","));
    logger.info("Going to sign transaction batch: {}", batchHashes);
    return txList.stream()
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
  }

  /**
   * Performs gRPC call to send transaction.
   *
   * @return ToriiResponse with transaction status stream
   */
  private ToriiResponse sendBuiltBatch(List<Transaction> txList) {
    final String batchHashes = txList.stream().map(Utils::toHexHash)
        .collect(Collectors.joining(","));
    txList.forEach(this::checkTransactionSignaturesCount);
    logger.info("Going to send transaction batch: {}", batchHashes);
    irohaAPI.transactionListSync(txList);
    return subscriptionStrategy
        .subscribe(irohaAPI, Utils.hash(txList.get(0)))
        .blockingLast();
  }

  private void checkTransactionSignaturesCount(Transaction transaction) {
    final int signaturesCount = transaction.getSignaturesCount();
    final int quorum = transaction.getPayload().getReducedPayload().getQuorum();
    if (signaturesCount < quorum) {
      final String msg = "Transaction " + Utils.toHexHash(transaction)
          + " does not have enough signatures: Quorum: " + quorum
          + " Signatures: " + signaturesCount;
      throw new IllegalArgumentException(msg);
    }
  }

  /**
   * Build HTTP REST response with handler.
   *
   * @param requestedTx - requested transaction
   * @param handler - handler for requested transaction
   * @param <T> - parameter of requested transaction
   * @return {@link Response HTTP} {@link HttpStatus 200} with transaction status stream <br> {@link
   * Response HTTP} {@link HttpStatus 422} if JSON supplied is incorrect or quorum is not satisfied
   * before sending
   * <br> {@link Response HTTP} {@link HttpStatus 500} if any other error occurred
   */
  private <T> Response buildResponse(T requestedTx, CheckedFunction<T, ToriiResponse> handler) {
    try {
      ToriiResponse res = handler.apply(requestedTx);
      String status = printer.print(res);
      logger.info("Got transaction status {}", status);
      return Response.ok(new ToriiResultResponse(res)).build();
    } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
      logger.error("Error during transaction processing", e);
      throw new BrvsException(e.getMessage(), e, MALFORMED_TRANSACTION);
    }
  }

  /**
   * A simple wrapper class for (de)serializing JSONed account id in Iroha
   */
  private static class AccountIdJsonWrapper {

    private String accountId;

    String getAccountId() {
      return accountId;
    }
  }

  /**
   * A simple wrapper class for serializing proper boolean result response
   */
  @JsonAutoDetect(fieldVisibility = Visibility.ANY)
  private static class AccountRegisteredResponse extends GenericStatusedResponse {

    private final boolean registered;

    AccountRegisteredResponse(boolean registered) {
      this.registered = registered;
    }
  }

  /**
   * A simple wrapper class for serializing proper {@link ValidationResult} response
   */
  @JsonAutoDetect(fieldVisibility = Visibility.ANY)
  private static class ValidationResultResponse extends GenericStatusedResponse {

    private final ValidationResult validationResult;

    public ValidationResultResponse(ValidationResult validationResult) {
      this.validationResult = validationResult;
    }
  }

  /**
   * A simple wrapper class for (de)serializing JSONed transaction with custom keys to be signed
   * with
   */
  private static class TransactionWithSignatoriesJsonWrapper {

    private String transaction;

    private List<String> keys;

    String getTransaction() {
      return transaction;
    }

    List<String> getKeys() {
      return keys;
    }
  }

  /**
   * A simple wrapper class for serializing proper {@link ToriiResponse} response
   */
  @JsonAutoDetect(fieldVisibility = Visibility.ANY)
  private static class ToriiResultResponse extends GenericStatusedResponse {

    private final ToriiResponse toriiResponse;

    public ToriiResultResponse(ToriiResponse toriiResponse) {
      this.toriiResponse = toriiResponse;
    }
  }
}
