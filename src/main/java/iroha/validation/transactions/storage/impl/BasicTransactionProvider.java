package iroha.validation.transactions.storage.impl;

import com.google.common.base.Strings;
import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Queries;
import iroha.protocol.Queries.BlocksQuery;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.storage.TransactionProvider;
import iroha.validation.util.ObservableRxList;
import java.security.KeyPair;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.BlocksQueryBuilder;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import jp.co.soramitsu.iroha.java.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final IrohaAPI irohaAPI;
  private final String accountId;
  private final KeyPair keyPair;
  private final Set<String> hashesKnown = new HashSet<>();
  private final ObservableRxList<Transaction> cache = new ObservableRxList<>();

  public BasicTransactionProvider(IrohaAPI irohaAPI, String accountId, KeyPair keyPair) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(accountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null or empty");
    }
    Objects.requireNonNull(keyPair, "Keypair must not be null");

    this.irohaAPI = irohaAPI;
    this.accountId = accountId;
    this.keyPair = keyPair;
    Executors.newScheduledThreadPool(1)
        .scheduleAtFixedRate(this::monitorIroha, 5, 2, TimeUnit.SECONDS);
  }

  public BasicTransactionProvider(String host, int port, String accountId, KeyPair keyPair) {
    this(new IrohaAPI(host, port), accountId, keyPair);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Observable<Transaction> getPendingTransactionsStreaming() {
    return cache.getObservable();
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

  private void monitorIroha() {
    Queries.Query query = Query.builder(accountId, 1).getPendingTransactions().buildSigned(keyPair);
    List<Transaction> pendingTransactions = irohaAPI.query(query).getTransactionsResponse()
        .getTransactionsList();
    // Add new
    pendingTransactions.forEach(transaction -> {
          String hex = Utils.toHex(Utils.hash(transaction));
          if (!hashesKnown.contains(hex)) {
            hashesKnown.add(hex);
            cache.add(transaction);
          }
        }
    );
    // Remove irrelevant
    List<String> pendingHashes = pendingTransactions
        .stream()
        .map(Utils::hash)
        .map(Utils::toHex)
        .collect(Collectors.toList());
    for (int i = 0; i < cache.size(); i++) {
      Transaction tx = cache.get(i);
      if (!pendingHashes.contains(Utils.toHex(Utils.hash(tx)))) {
        cache.remove(tx);
        i--;
      }
    }
  }
}
