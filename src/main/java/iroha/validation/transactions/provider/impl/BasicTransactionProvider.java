package iroha.validation.transactions.provider.impl;

import com.google.common.base.Strings;
import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.Queries;
import iroha.protocol.Queries.BlocksQuery;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.verdict.ValidationResult;
import iroha.validation.verdict.Verdict;
import java.security.KeyPair;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jp.co.soramitsu.iroha.java.BlocksQueryBuilder;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final IrohaAPI irohaAPI;
  // BRVS account id to query Iroha
  private final String accountId;
  // BRVS keypair to query Iroha
  private final KeyPair keyPair;
  private final TransactionVerdictStorage transactionVerdictStorage;
  private final CacheProvider cacheProvider;
  private boolean isStarted;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(4);
  // Accounts to monitor pending tx
  private final Set<String> accountsToMonitor = new HashSet<>();

  @Autowired
  public BasicTransactionProvider(IrohaAPI irohaAPI,
      String accountId,
      KeyPair keyPair,
      TransactionVerdictStorage transactionVerdictStorage,
      CacheProvider cacheProvider) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(accountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null or empty");
    }
    Objects.requireNonNull(keyPair, "Keypair must not be null");
    Objects.requireNonNull(transactionVerdictStorage, "TransactionVerdictStorage must not be null");
    Objects.requireNonNull(cacheProvider, "CacheProvider must not be null");

    this.irohaAPI = irohaAPI;
    this.accountId = accountId;
    this.keyPair = keyPair;
    this.transactionVerdictStorage = transactionVerdictStorage;
    this.cacheProvider = cacheProvider;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Observable<Transaction> getPendingTransactionsStreaming() {
    if (!isStarted) {
      logger.info("Starting pending transactions streaming");
      isStarted = true;
      executorService.scheduleAtFixedRate(this::monitorIrohaPending, 0, 2, TimeUnit.SECONDS);
      executorService.schedule(this::processCommittedTransactions, 0, TimeUnit.SECONDS);
      executorService
          .scheduleAtFixedRate(this::monitorRejectedTransactions, 1, 2, TimeUnit.SECONDS);
      executorService
          .scheduleAtFixedRate(cacheProvider::takeNotlockedFromCache, 0, 1, TimeUnit.SECONDS);
    }
    return cacheProvider.getObservable();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Observable<Block> getBlockStreaming() {
    BlocksQuery query = new BlocksQueryBuilder(accountId, Instant.now(), 1).buildSigned(keyPair);

    return irohaAPI.blocksQuery(query).map(response -> {
          logger.info(
              "New Iroha block arrived. Height " + response.getBlockResponse().getBlock().getBlockV1()
                  .getPayload().getHeight());
          return response.getBlockResponse().getBlock();
        }
    );
  }

  @Override
  public void register(String accountId) {
    synchronized (accountsToMonitor) {
      accountsToMonitor.add(accountId);
    }
  }

  private Set<Transaction> getAllPendingTransactions() {
    Set<Transaction> pendingTransactions = new HashSet<>();
    synchronized (accountsToMonitor) {
      accountsToMonitor.forEach(account -> {
            Queries.Query query = Query.builder(account, 1)
                .getPendingTransactions()
                .buildSigned(keyPair);
            pendingTransactions
                .addAll(irohaAPI.query(query)
                    .getTransactionsResponse()
                    .getTransactionsList()
                );
          }
      );
    }
    return pendingTransactions;
  }

  private void monitorRejectedTransactions() {
    synchronized (accountsToMonitor) {
      accountsToMonitor.forEach(account -> {
            ValidationResult verdict = getPendingValidationResult(account);
            if (verdict != null && verdict.getStatus().equals(Verdict.REJECTED)) {
              cacheProvider.removeIfPending(account);
            }
          }
      );
    }
  }

  private ValidationResult getPendingValidationResult(String account) {
    return transactionVerdictStorage
        .getTransactionVerdict(cacheProvider.getAccountPendingTransactionHash(account));
  }

  private void monitorIrohaPending() {
    getAllPendingTransactions().forEach(transaction -> {
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

  private void processCommittedTransactions() {
    getBlockStreaming().subscribe(block -> {
          final List<Transaction> blockTransactions = block
              .getBlockV1()
              .getPayload()
              .getTransactionsList();

          if (blockTransactions != null) {
            blockTransactions.forEach(this::tryToRemoveLock);
          }
        }
    );
  }

  private void tryToRemoveLock(Transaction transaction) {
    // committed transfer transactions
    if (transaction.getPayload().getReducedPayload().getCommandsList().stream()
        .anyMatch(Command::hasTransferAsset)) {
      cacheProvider.removeIfPending(ValidationUtils.getTxAccountId(transaction));
    }
  }

  @Override
  public void close() {
    executorService.shutdownNow();
  }
}
