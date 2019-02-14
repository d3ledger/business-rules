package iroha.validation.transactions.provider.impl;

import com.google.protobuf.ProtocolStringList;
import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block_v1.Payload;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final TransactionVerdictStorage transactionVerdictStorage;
  private final CacheProvider cacheProvider;
  private boolean isStarted;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(2);
  // Accounts to monitor pending tx
  private final Set<String> accountsToMonitor = new HashSet<>();

  private final IrohaHelper irohaHelper;

  @Autowired
  public BasicTransactionProvider(
      TransactionVerdictStorage transactionVerdictStorage,
      CacheProvider cacheProvider,
      IrohaHelper irohaHelper
  ) {
    Objects.requireNonNull(transactionVerdictStorage, "TransactionVerdictStorage must not be null");
    Objects.requireNonNull(cacheProvider, "CacheProvider must not be null");

    this.transactionVerdictStorage = transactionVerdictStorage;
    this.cacheProvider = cacheProvider;
    this.irohaHelper = irohaHelper;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Observable<Transaction> getPendingTransactionsStreaming() {
    if (!isStarted) {
      logger.info("Starting pending transactions streaming");
      executorService.scheduleAtFixedRate(this::monitorIrohaPending, 0, 2, TimeUnit.SECONDS);
      executorService.schedule(this::processBlockTransactions, 0, TimeUnit.SECONDS);
      isStarted = true;
    }
    return cacheProvider.getObservable();
  }

  @Override
  public void register(String accountId) {
    synchronized (accountsToMonitor) {
      accountsToMonitor.add(accountId);
    }
  }

  private void monitorIrohaPending() {
    synchronized (accountsToMonitor) {
      irohaHelper.getAllPendingTransactions(accountsToMonitor).forEach(transaction -> {
            // if only BRVS signatory remains
            if (transaction.getPayload().getReducedPayload().getQuorum() -
                transaction.getSignaturesCount() == 1) {
              String hex = ValidationUtils.hexHash(transaction);
              if (!transactionVerdictStorage.isHashPresentInStorage(hex)) {
                transactionVerdictStorage.markTransactionPending(hex);
                cacheProvider.put(transaction);
              }
            }
          }
      );
    }
  }

  private void processBlockTransactions() {
    irohaHelper.getBlockStreaming().subscribe(block -> {
          final Payload payload = block
              .getBlockV1()
              .getPayload();

      processRejected(payload.getRejectedTransactionsHashesList());
      processCommitted(payload.getTransactionsList());
        }
    );
  }

  private void processRejected(ProtocolStringList rejectedHashes) {
    if (rejectedHashes != null) {
      rejectedHashes.forEach(this::tryToRemoveLock);
    }
  }

  private void processCommitted(List<Transaction> blockTransactions) {
    if (blockTransactions != null) {
      blockTransactions.forEach(this::tryToRemoveLock);
    }
  }

  private void tryToRemoveLock(Transaction transaction) {
    tryToRemoveLock(ValidationUtils.hexHash(transaction));
  }

  private void tryToRemoveLock(String hash) {
    String account = cacheProvider.getAccountBlockedBy(hash);
    if (account != null) {
      cacheProvider.unlockPendingAccount(account);
    }
  }

  @Override
  public void close() {
    executorService.shutdownNow();
  }
}
