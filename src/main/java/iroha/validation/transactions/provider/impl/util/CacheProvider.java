package iroha.validation.transactions.provider.impl.util;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.utils.ValidationUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class CacheProvider {

  // Local BRVS cache
  private final Map<String, List<Transaction>> cache = new HashMap<>();
  // Iroha accounts awaiting for the previous transaction completion
  private final Set<String> pendingAccounts = new HashSet<>();
  // Observable
  private final PublishSubject<Transaction> subject = PublishSubject.create();

  public synchronized void put(Transaction transaction) {
    final String accountId = ValidationUtils.getTxAccountId(transaction);
    if (!cache.containsKey(accountId)) {
      cache.put(accountId, new ArrayList<>());
    }
    cache.get(accountId).add(transaction);
  }

  private synchronized void removeAccountTransaction(String accountId, Transaction transaction) {
    if (cache.containsKey(accountId)) {
      cache.get(accountId).remove(transaction);
      if (transaction.getPayload().getReducedPayload().getCommandsList().stream()
          .anyMatch(Command::hasTransferAsset)) {
        pendingAccounts.add(accountId);
      }
      subject.onNext(transaction);
    }
  }

  public synchronized void removePending(String account) {
    pendingAccounts.remove(account);
  }

  public synchronized boolean isPending(String account) {
    return pendingAccounts.contains(account);
  }

  public synchronized List<Transaction> getAccountTransactions(String account) {
    return cache.get(account);
  }

  public synchronized Observable<Transaction> getObservable() {
    return subject;
  }

  public synchronized void manageCache() {
    cache.keySet().forEach(account -> {
          if (!isPending(account)) {
            takeNextTx(account);
          }
        }
    );
  }

  private synchronized void takeNextTx(String account) {
    List<Transaction> transactions = getAccountTransactions(account);
    if (!CollectionUtils.isEmpty(transactions)) {
      removeAccountTransaction(account, transactions.get(0));
    }
  }
}
