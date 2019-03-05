package iroha.validation.transactions.provider.impl;

import io.reactivex.Observable;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.listener.IrohaReliableChainListener;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.provider.impl.util.UserQuorumProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final TransactionVerdictStorage transactionVerdictStorage;
  private final CacheProvider cacheProvider;
  private final UserQuorumProvider userQuorumProvider;
  private boolean isStarted;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);
  // Accounts to monitor pending tx
  private final Set<String> accountsToMonitor = new HashSet<>();

  private final IrohaReliableChainListener irohaReliableChainListener;

  public BasicTransactionProvider(
      TransactionVerdictStorage transactionVerdictStorage,
      CacheProvider cacheProvider,
      UserQuorumProvider userQuorumProvider,
      IrohaReliableChainListener irohaReliableChainListener
  ) {
    Objects.requireNonNull(transactionVerdictStorage, "TransactionVerdictStorage must not be null");
    Objects.requireNonNull(cacheProvider, "CacheProvider must not be null");

    this.transactionVerdictStorage = transactionVerdictStorage;
    this.cacheProvider = cacheProvider;
    this.userQuorumProvider = userQuorumProvider;
    this.irohaReliableChainListener = irohaReliableChainListener;
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
      executorService.schedule(this::processRejectedTransactions, 0, TimeUnit.SECONDS);
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
      irohaReliableChainListener.getAllPendingTransactions(accountsToMonitor)
          .forEach(transaction -> {
                // if only BRVS signatory remains
                if (transaction.getSignaturesCount() >= userQuorumProvider.getUserQuorum(
                    transaction.getPayload().getReducedPayload().getCreatorAccountId())) {
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

  private void processRejectedTransactions() {
    transactionVerdictStorage.getRejectedTransactionsHashesStreaming()
        .subscribe(this::tryToRemoveLock);
  }

  private void processBlockTransactions() {
    irohaReliableChainListener.getBlockStreaming().subscribe(block ->
          /*
          We do not process rejected hashes of blocks in order to support fail fast behavior
          BRVS fake key pair leads to STATELESS_INVALID status so such transactions
          are not presented in ledger blocks at all
           */
        processCommitted(
            block
                .getBlockV1()
                .getPayload()
                .getTransactionsList()
        )
    );
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
  public void close() throws IOException {
    executorService.shutdownNow();
    irohaReliableChainListener.close();
  }
}
