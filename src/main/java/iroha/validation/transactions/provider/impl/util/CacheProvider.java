package iroha.validation.transactions.provider.impl.util;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.utils.ValidationUtils;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class CacheProvider {

  // Local BRVS cache
  private final Map<String, List<Transaction>> cache = new HashMap<>();
  // Iroha accounts awaiting for the previous transaction completion
  private final Map<String, String> pendingAccounts = new HashMap<>();
  // Observable
  private final PublishSubject<Transaction> subject = PublishSubject.create();

  public synchronized void put(Transaction transaction) {
    final String accountId = ValidationUtils.getTxAccountId(transaction);
    if (!pendingAccounts.containsKey(accountId)) {
      // do not even put in cache if possible
      consumeAndLockAccountByTransactionIfNeeded(accountId, transaction);
      return;
    }
    if (!cache.containsKey(accountId)) {
      cache.put(accountId, new LinkedList<>());
    }
    cache.get(accountId).add(transaction);
  }

  private synchronized void consumeNextAccountTransaction(String accountId) {
    List<Transaction> accountTransactions = cache.get(accountId);
    if (!CollectionUtils.isEmpty(accountTransactions)) {
      Transaction transaction = accountTransactions.remove(0);
      consumeAndLockAccountByTransactionIfNeeded(accountId, transaction);
    }
  }

  private synchronized void consumeAndLockAccountByTransactionIfNeeded(
      String account,
      Transaction transaction) {

    if (transaction.getPayload().getReducedPayload().getCommandsList().stream()
        .anyMatch(Command::hasTransferAsset)) {
      pendingAccounts.put(account, ValidationUtils.hexHash(transaction));
    }
    subject.onNext(transaction);
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
      consumeNextAccountTransaction(account);
    }
  }

  public synchronized Observable<Transaction> getObservable() {
    return subject;
  }
}
