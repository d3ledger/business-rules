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
    if (!cache.containsKey(accountId)) {
      cache.put(accountId, new LinkedList<>());
    }
    cache.get(accountId).add(transaction);
    if (!pendingAccounts.containsKey(accountId)) {
      subject.onNext(transaction);
    }
  }

  private synchronized void consumeNextAccountTransaction(String accountId) {
    if (cache.containsKey(accountId)) {
      Transaction transaction = cache.get(accountId).get(0);
      cache.get(accountId).remove(transaction);
      if (transaction.getPayload().getReducedPayload().getCommandsList().stream()
          .anyMatch(Command::hasTransferAsset)) {
        pendingAccounts.put(accountId, ValidationUtils.hexHash(transaction));
      }
      subject.onNext(transaction);
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
      consumeNextAccountTransaction(account);
    }
  }

  public synchronized Observable<Transaction> getObservable() {
    return subject;
  }
}
