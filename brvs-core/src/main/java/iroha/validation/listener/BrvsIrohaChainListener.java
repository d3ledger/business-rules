/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.listener;

import com.d3.commons.config.RMQConfig;
import com.d3.commons.sidechain.iroha.ReliableIrohaChainListener;
import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import java.io.Closeable;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import kotlin.Pair;

public class BrvsIrohaChainListener implements Closeable {

  private static final String BRVS_QUEUE_RMQ_NAME = "brvs";

  private final IrohaAPI irohaAPI;
  // BRVS keypair to query Iroha
  private final KeyPair brvsKeyPair;
  private final String brvsAccountId;
  private final KeyPair userKeyPair;
  private final ReliableIrohaChainListener irohaChainListener;
  private final ConcurrentMap<String, QueryAPI> queryAPIMap = new ConcurrentHashMap<>();

  public BrvsIrohaChainListener(
      RMQConfig rmqConfig,
      QueryAPI queryAPI,
      KeyPair userKeyPair) {
    Objects.requireNonNull(queryAPI, "RMQ config must not be null");
    Objects.requireNonNull(queryAPI, "Query API must not be null");
    Objects.requireNonNull(userKeyPair, "User Keypair must not be null");

    irohaChainListener = new ReliableIrohaChainListener(rmqConfig, BRVS_QUEUE_RMQ_NAME);
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
    queryAPIMap.putIfAbsent(accountId, new QueryAPI(irohaAPI, accountId, keyPair));
    return constructBatches(
        queryAPIMap.get(accountId)
            .getPendingTransactions()
            .getTransactionsList()
    );
  }

  /**
   * Converts Iroha transactions to brvs batches
   *
   * @param transactions transaction list to be converted
   * @return {@link List} of {@link TransactionBatch} of input
   */
  private List<TransactionBatch> constructBatches(List<Transaction> transactions) {
    List<TransactionBatch> transactionBatches = new ArrayList<>();
    for (int i = 0; i < transactions.size(); ) {
      final List<Transaction> transactionListForBatch = new ArrayList<>();
      final int hashesCount = transactions
          .get(i)
          .getPayload()
          .getBatch()
          .getReducedHashesCount();
      final int toInclude = hashesCount == 0 ? 1 : hashesCount;

      for (int j = 0; j < toInclude; j++) {
        transactionListForBatch.add(transactions.get(i + j));
      }
      i += toInclude;
      transactionBatches.add(new TransactionBatch(transactionListForBatch));
    }
    return transactionBatches;
  }

  public Observable<Block> getBlockStreaming() {
    return irohaChainListener.getBlockObservable().get().map(Pair::getFirst);
  }

  public void listen() {
    irohaChainListener.listen();
  }

  @Override
  public void close() {
    irohaChainListener.close();
  }
}
