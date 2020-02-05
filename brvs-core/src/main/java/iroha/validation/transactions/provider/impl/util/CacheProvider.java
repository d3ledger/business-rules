/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider.impl.util;

import static iroha.validation.utils.ValidationUtils.hexHash;

import com.google.common.collect.Iterables;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.utils.ValidationUtils;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

/**
 * Service in-memory queue of transactions. Implements isolated queue processing for each user.
 */
public class CacheProvider {

  private static final Logger logger = LoggerFactory.getLogger(CacheProvider.class);

  // Local BRVS cache
  private final Map<String, Set<TransactionBatch>> cache = new HashMap<>();
  // Iroha accounts awaiting for the previous transaction completion
  private final Map<String, String> pendingAccounts = new HashMap<>();
  // Observable
  private final PublishSubject<TransactionBatch> subject = PublishSubject.create();

  // Puts a transaction in the corresponding user queue if needed
  // Or immediately consumes it if possible
  public synchronized void put(TransactionBatch transactionBatch) {
    if (isBatchUnlocked(transactionBatch)) {
      // do not even put in cache if possible
      consumeAndLockAccountByTransactionIfNeeded(transactionBatch);
      return;
    }
    final String accountId = transactionBatch.getBatchInitiator();
    if (!cache.containsKey(accountId)) {
      cache.put(accountId, new HashSet<>());
    }
    cache.get(accountId).add(transactionBatch);
    logger.info("Put transactions {} in cache queue", hexHash(transactionBatch));
  }

  // Initiates consuming of a user queue
  private synchronized void consumeUnlockedTransactionBatches(String accountId) {
    final Set<TransactionBatch> accountTransactions = cache.get(accountId);
    if (!CollectionUtils.isEmpty(accountTransactions)) {
      final TransactionBatch transactionBatch = accountTransactions
          .stream()
          .filter(this::isBatchUnlocked)
          .findAny()
          .orElse(null);
      if (transactionBatch != null) {
        accountTransactions.remove(transactionBatch);
        if (accountTransactions.isEmpty()) {
          cache.remove(accountId);
        }
        consumeAndLockAccountByTransactionIfNeeded(transactionBatch);
        consumeUnlockedTransactionBatches(accountId);
      }
    }
  }

  // Consumes a single transaction of the queue and locks a user queue from next consuming if needed
  private synchronized void consumeAndLockAccountByTransactionIfNeeded(
      TransactionBatch transactionBatch) {
    if (transactionBatch != null) {
      transactionBatch.forEach(transaction ->
          transaction.getPayload().getReducedPayload()
              .getCommandsList()
              .stream()
              .filter(Command::hasTransferAsset)
              .map(Command::getTransferAsset)
              .forEach(transferAsset -> {
                final String srcAccountId = transferAsset.getSrcAccountId();
                final String hash = hexHash(transaction);
                logger.info("Locked {} account by transfer hash {}", srcAccountId, hash);
                pendingAccounts.put(srcAccountId, hash);
              })
      );
      logger.info("Publishing {} transactions for validation", hexHash(transactionBatch));
      subject.onNext(transactionBatch);
    }
  }

  public synchronized void unlockPendingAccountsByHash(String txHash) {
    unlockPendingAccounts(getAccountsBlockedBy(txHash));
  }

  // Returns accounts locked by a transaction hash provided
  public synchronized Set<String> getAccountsBlockedBy(String txHash) {
    return pendingAccounts.entrySet()
        .stream()
        .filter(entry -> entry.getValue().equals(txHash))
        .map(Entry::getKey)
        .collect(Collectors.toSet());
  }

  public synchronized void unlockPendingAccount(String account) {
    unlockPendingAccounts(Collections.singleton(account));
  }

  // Unlocks accounts and continues consuming
  public synchronized void unlockPendingAccounts(Iterable<String> accounts) {
    if (!Iterables.isEmpty(accounts)) {
      accounts.forEach(pendingAccounts::remove);
      logger.info("Unlocked {} accounts", accounts);
      accounts.forEach(this::consumeUnlockedTransactionBatches);
    }
  }

  public synchronized Observable<TransactionBatch> getObservable() {
    return subject;
  }

  // Returns all transactions from all user queues
  public synchronized Iterable<Transaction> getTransactions() {
    return Iterables.concat(StreamSupport
        .stream(Iterables.concat(cache.values()).spliterator(), false)
        .map(TransactionBatch::getTransactionList).distinct().collect(Collectors.toList()));
  }

  // Checks if the batch lead to locking of the queue
  private boolean isBatchUnlocked(TransactionBatch transactionBatch) {
    return transactionBatch.stream().noneMatch(transaction ->
        transaction.getPayload().getReducedPayload()
            .getCommandsList()
            .stream()
            .filter(Command::hasTransferAsset)
            .map(Command::getTransferAsset)
            .map(TransferAsset::getSrcAccountId)
            .anyMatch(pendingAccounts::containsKey)
    );
  }
}
