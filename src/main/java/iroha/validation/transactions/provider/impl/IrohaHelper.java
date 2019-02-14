package iroha.validation.transactions.provider.impl;

import com.google.common.base.Strings;
import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass;
import iroha.protocol.QryResponses;
import iroha.protocol.Queries;
import iroha.protocol.TransactionOuterClass;
import java.security.KeyPair;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import jp.co.soramitsu.iroha.java.BlocksQueryBuilder;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Class that helps with different iroha interaction
 */
@Component
public class IrohaHelper {

  private static final Logger logger = LoggerFactory.getLogger(IrohaHelper.class);

  private final IrohaAPI irohaAPI;
  // BRVS account id to query Iroha
  private final String accountId;
  // BRVS keypair to query Iroha
  private final KeyPair keyPair;

  @Autowired
  public IrohaHelper(
      IrohaAPI irohaAPI,
      String accountId,
      KeyPair keyPair
  ) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(accountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null or empty");
    }
    Objects.requireNonNull(keyPair, "Keypair must not be null");

    this.irohaAPI = irohaAPI;
    this.accountId = accountId;
    this.keyPair = keyPair;
  }


  /**
   * Queries pending transactions for specific users
   *
   * @param accountsToMonitor users that transactions should be queried for
   * @return set of transactions that are in pending state
   */
  Set<TransactionOuterClass.Transaction> getAllPendingTransactions(Set<String> accountsToMonitor) {
    Set<TransactionOuterClass.Transaction> pendingTransactions = new HashSet<>();
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
    return pendingTransactions;
  }

  /**
   * Method providing new blocks coming from Iroha
   *
   * @return {@link Observable} of Iroha proto {@link QryResponses.BlockQueryResponse} block
   */
  Observable<BlockOuterClass.Block> getBlockStreaming() {
    Queries.BlocksQuery query = new BlocksQueryBuilder(accountId, Instant.now(), 1)
        .buildSigned(keyPair);
    return irohaAPI.blocksQuery(query).map(response -> {
          logger.info(
              "New Iroha block arrived. Height " + response.getBlockResponse().getBlock().getBlockV1()
                  .getPayload().getHeight());
          return response.getBlockResponse().getBlock();
        }
    );
  }
}
