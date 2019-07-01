/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider.impl.util;

import com.google.common.collect.Iterables;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.utils.ValidationUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.util.CollectionUtils;

public class CacheProvider {

  // Local BRVS cache
  private final Map<String, Set<TransactionBatch>> cache = new HashMap<>();
  // Iroha accounts awaiting for the previous transaction completion
  private final Map<String, String> pendingAccounts = new HashMap<>();
  // Observable
  private final PublishSubject<TransactionBatch> subject = PublishSubject.create();

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
  }

  private synchronized void consumeNextTransactionBatch(String accountId) {
    Set<TransactionBatch> accountTransactions = cache.get(accountId);
    if (!CollectionUtils.isEmpty(accountTransactions)) {
      final TransactionBatch transactionBatch = accountTransactions.stream()
          .filter(this::isBatchUnlocked).findAny().orElse(null);
      if (transactionBatch != null) {
        final String txAccountId = transactionBatch.getBatchInitiator();
        final Set<TransactionBatch> accountBatches = cache.get(txAccountId);
        accountBatches.remove(transactionBatch);
        if (accountBatches.isEmpty()) {
          cache.remove(txAccountId);
        }
      }
      consumeAndLockAccountByTransactionIfNeeded(transactionBatch);
    }
  }

  private synchronized void consumeAndLockAccountByTransactionIfNeeded(
      TransactionBatch transactionBatch) {
    if (transactionBatch != null) {
      transactionBatch.forEach(transaction -> {
            if (transaction.getPayload().getReducedPayload().getCommandsList().stream()
                .anyMatch(Command::hasTransferAsset)) {
              pendingAccounts.put(
                  ValidationUtils.getTxAccountId(transaction),
                  ValidationUtils.hexHash(transaction)
              );
            }
          }
      );
      subject.onNext(transactionBatch);
    }
  }

  public synchronized String getAccountBlockedBy(String txHash) {
    for (Map.Entry<String, String> entry : pendingAccounts.entrySet()) {
      if (entry.getValue().equals(txHash)) {
        return entry.getKey();
      }
    }
    return null;
  }

  public synchronized void unlockPendingAccount(String account) {
    if (pendingAccounts.remove(account) != null) {
      consumeNextTransactionBatch(account);
    }
  }

  public synchronized Observable<TransactionBatch> getObservable() {
    return subject;
  }

  public synchronized Iterable<Transaction> getTransactions() {
    return Iterables.concat(StreamSupport
        .stream(Iterables.concat(cache.values()).spliterator(), false)
        .map(TransactionBatch::getTransactionList).distinct().collect(Collectors.toList()));
  }

  private boolean isBatchUnlocked(TransactionBatch transactionBatch) {
    for (Transaction transaction : transactionBatch) {
      final String accountId = ValidationUtils.getTxAccountId(transaction);
      if (pendingAccounts.containsKey(accountId)) {
        return false;
      }
    }
    return true;
  }
}
