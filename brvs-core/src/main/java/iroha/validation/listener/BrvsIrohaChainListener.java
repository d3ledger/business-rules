/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.listener;

import com.d3.chainadapter.client.BlockSubscription;
import com.d3.chainadapter.client.RMQConfig;
import com.d3.chainadapter.client.ReliableIrohaChainListener4J;
import io.reactivex.Observable;
import iroha.protocol.QryResponses.ErrorResponse;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import java.io.Closeable;
import java.io.IOException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import jp.co.soramitsu.iroha.java.ErrorResponseException;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import jp.co.soramitsu.iroha.java.QueryAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper class that reuses {@link ReliableIrohaChainListener4J} and provides additional
 * functionality to make friendly processable abstraction on top of Iroha batches
 */
public class BrvsIrohaChainListener implements Closeable {

  private static final String BRVS_QUEUE_RMQ_NAME = "brvs";
  private static final Logger logger = LoggerFactory.getLogger(BrvsIrohaChainListener.class);
  private static AtomicLong counter = new AtomicLong(1);

  private final IrohaAPI irohaAPI;
  // BRVS keypair to query Iroha
  private final KeyPair brvsKeyPair;
  private final String brvsAccountId;
  private final KeyPair userKeyPair;
  private final ReliableIrohaChainListener4J irohaChainListener;

  public BrvsIrohaChainListener(
      RMQConfig rmqConfig,
      QueryAPI queryAPI,
      KeyPair userKeyPair) {
    Objects.requireNonNull(queryAPI, "RMQ config must not be null");
    Objects.requireNonNull(queryAPI, "Query API must not be null");
    Objects.requireNonNull(userKeyPair, "User Keypair must not be null");

    irohaChainListener = new ReliableIrohaChainListener4J(rmqConfig, BRVS_QUEUE_RMQ_NAME, false);
    this.irohaAPI = queryAPI.getApi();
    this.brvsAccountId = queryAPI.getAccountId();
    this.brvsKeyPair = queryAPI.getKeyPair();
    this.userKeyPair = userKeyPair;
  }

  /**
   * Queries pending transactions for specific users
   *
   * @param accountsToMonitor users that transactions should be queried for
   * @return set of transactions that are in pending state
   */
  public Set<TransactionBatch> getAllPendingTransactions(Iterable<String> accountsToMonitor) {
    Set<TransactionBatch> pendingTransactions = new HashSet<>(
        getPendingTransactions(brvsAccountId, brvsKeyPair)
    );
    accountsToMonitor.forEach(account ->
        pendingTransactions.addAll(getPendingTransactions(account, userKeyPair))
    );
    logger.info("Got {} pending batches from Iroha", pendingTransactions.size());
    return pendingTransactions;
  }

  /**
   * Queries pending transactions for a specified account and keypair
   *
   * @param accountId user that transactions should be queried for
   * @param keyPair user keypair
   * @return list of user transactions that are in pending state
   */
  private List<TransactionBatch> getPendingTransactions(String accountId, KeyPair keyPair) {
    return constructBatches(executeQueryFor(accountId, keyPair));
  }

  /**
   * Returns a relevant query api instance
   *
   * @param accountId user that transactions should be queried for
   * @param keyPair user keypair
   * @return user pending transactions list
   */
  private List<Transaction> executeQueryFor(String accountId, KeyPair keyPair) {
    final QueryResponse queryResponse = irohaAPI.query(
        Query.builder(accountId, counter.getAndIncrement())
            .getPendingTransactions()
            .buildSigned(keyPair)
    );

    if (queryResponse.hasErrorResponse()) {
      ErrorResponse errorResponse = queryResponse.getErrorResponse();
      throw new ErrorResponseException(errorResponse);
    }

    return queryResponse
        .getTransactionsResponse()
        .getTransactionsList();
  }

  /**
   * Converts Iroha transactions to brvs batches
   *
   * @param transactions transaction list to be converted
   * @return {@link List} of {@link TransactionBatch} of input
   */
  private List<TransactionBatch> constructBatches(List<Transaction> transactions) {
    final List<TransactionBatch> transactionBatches = new ArrayList<>();
    // batch size for every transaction in the list
    // used to shift through processed batch sublist
    int batchSize;
    for (int i = 0; i < transactions.size(); i += batchSize) {
      final List<Transaction> transactionListForBatch = new ArrayList<>();
      final int hashesCount = transactions
          .get(i)
          .getPayload()
          .getBatch()
          .getReducedHashesCount();
      batchSize = hashesCount == 0 ? 1 : hashesCount;

      for (int j = 0; j < batchSize; j++) {
        transactionListForBatch.add(transactions.get(i + j));
      }
      transactionBatches.add(new TransactionBatch(transactionListForBatch));
    }
    return transactionBatches;
  }

  /**
   * Iroha blocks observable entrypoint
   *
   * @return {@link Observable} of {@link BlockSubscription}
   */
  public Observable<BlockSubscription> getBlockStreaming() {
    return irohaChainListener.getBlockObservable();
  }

  public void listen() {
    irohaChainListener.listen();
  }

  @Override
  public void close() throws IOException {
    irohaChainListener.close();
  }
}
