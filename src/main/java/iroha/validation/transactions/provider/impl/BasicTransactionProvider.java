package iroha.validation.transactions.provider.impl;

import com.google.common.base.Strings;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.Queries;
import iroha.protocol.Queries.BlocksQuery;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jp.co.soramitsu.iroha.java.BlocksQueryBuilder;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import jp.co.soramitsu.iroha.java.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final IrohaAPI irohaAPI;
  // BRVS account id to query Iroha
  private final String accountId;
  // BRVS keypair to query Iroha
  private final KeyPair keyPair;
  private final TransactionVerdictStorage transactionVerdictStorage;
  // Local BRVS cache
  private final Map<String, List<Transaction>> cache = new HashMap<>();
  // Iroha accounts awaiting for the previous transaction completion
  private final Set<String> pendingAccounts = Collections.synchronizedSet(new HashSet<>());
  // Observable
  private final PublishSubject<Transaction> subject = PublishSubject.create();
  private boolean isStarted;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);

  @Autowired
  public BasicTransactionProvider(IrohaAPI irohaAPI,
      String accountId,
      KeyPair keyPair,
      TransactionVerdictStorage transactionVerdictStorage) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(accountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null or empty");
    }
    Objects.requireNonNull(keyPair, "Keypair must not be null");
    Objects.requireNonNull(transactionVerdictStorage, "TransactionVerdictStorage must not be null");

    this.irohaAPI = irohaAPI;
    this.accountId = accountId;
    this.keyPair = keyPair;
    this.transactionVerdictStorage = transactionVerdictStorage;
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
      executorService.schedule(this::monitorNewBlocks, 2, TimeUnit.SECONDS);
      executorService.scheduleAtFixedRate(this::manageCache, 2, 2, TimeUnit.SECONDS);
    }
    return subject;
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

  private void monitorIrohaPending() {
    Queries.Query query = Query.builder(accountId, 1).getPendingTransactions().buildSigned(keyPair);
    List<Transaction> pendingTransactions = irohaAPI.query(query).getTransactionsResponse()
        .getTransactionsList();
    pendingTransactions.forEach(transaction -> {
          // if only BRVS signatory remains
          if (transaction.getPayload().getReducedPayload().getQuorum() -
              transaction.getSignaturesCount() == 1) {
            String hex = Utils.toHex(Utils.hash(transaction));
            if (!transactionVerdictStorage.isHashPresentInStorage(hex)) {
              transactionVerdictStorage.markTransactionPending(hex);
              put(transaction);
            }
          }
        }
    );
  }

  private void monitorNewBlocks() {
    getBlockStreaming().subscribe(block -> {
          final List<Transaction> blockTransactions = block
              .getBlockV1()
              .getPayload()
              .getTransactionsList();

          if (blockTransactions != null) {
            // committed transfer transactions
            blockTransactions.forEach(
                transaction -> {
                  if (transaction.getPayload().getReducedPayload().getCommandsList().stream()
                      .anyMatch(Command::hasTransferAsset)) {
                    pendingAccounts.remove(getTxAccountId(transaction));
                  }
                }
            );
          }
        }
    );
  }

  private void manageCache() {
    cache.keySet().forEach(account -> {
          if (!pendingAccounts.contains(account)) {
            List<Transaction> transactions = cache.get(account);
            if (!CollectionUtils.isEmpty(transactions)) {
              remove(transactions.get(0));
            }
          }
        }
    );
  }

  @Override
  public void close() {
    executorService.shutdownNow();
  }

  private void put(Transaction transaction) {
    final String accountId = getTxAccountId(transaction);
    if (!cache.containsKey(accountId)) {
      cache.put(accountId, new ArrayList<>());
    }
    cache.get(accountId).add(transaction);
  }

  private void remove(Transaction transaction) {
    final String accountId = getTxAccountId(transaction);
    if (cache.containsKey(accountId)) {
      cache.get(accountId).remove(transaction);
      pendingAccounts.add(accountId);
      subject.onNext(transaction);
      if (cache.get(accountId).size() == 0) {
        cache.remove(accountId);
      }
    }
  }

  private String getTxAccountId(final Transaction transaction) {
    return transaction.getPayload().getReducedPayload().getCreatorAccountId();
  }
}
