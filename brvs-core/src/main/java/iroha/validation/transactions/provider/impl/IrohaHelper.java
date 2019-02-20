package iroha.validation.transactions.provider.impl;

import com.google.common.base.Strings;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.BlockOuterClass;
import iroha.protocol.QryResponses;
import iroha.protocol.Queries;
import iroha.protocol.TransactionOuterClass;
import java.io.IOException;
import java.security.KeyPair;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
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

  private final String RMQHost;
  private final Integer RMQPort;

  @Autowired
  public IrohaHelper(
      IrohaAPI irohaAPI,
      String accountId,
      KeyPair keyPair,
      String RMQHost,
      Integer RMQPort) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(accountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null or empty");
    }
    Objects.requireNonNull(keyPair, "Keypair must not be null");

    this.irohaAPI = irohaAPI;
    this.accountId = accountId;
    this.keyPair = keyPair;
    this.RMQHost = RMQHost;
    this.RMQPort = RMQPort;
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
  public Observable<BlockOuterClass.Block> getBlockStreaming() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RMQHost);
    factory.setPort(RMQPort);
    Connection conn = null;
    Channel ch = null;
    String queue = null;
    try {
      conn = factory.newConnection();
      ch = conn.createChannel();
      ch.exchangeDeclare("iroha", "fanout", true);
      queue = ch.queueDeclare("", true, false, false, null).getQueue();
      ch.queueBind(queue, "iroha", "");
    } catch (IOException e) {
      e.printStackTrace();
    } catch (TimeoutException e) {
      e.printStackTrace();
    }

    PublishSubject<Delivery> source = PublishSubject.create();
    DeliverCallback deliverCallback = (consumerTag, delivery) -> {
      source.onNext(delivery);
    };

    try {
      ch.basicConsume(queue, true, deliverCallback, consumerTag -> {
      });
    } catch (IOException e) {
      e.printStackTrace();
    }

    logger.info("On subscribe to Iroha chain");

    return source.map(delivery -> {
      System.out.println(delivery.getBody());
      BlockOuterClass.Block block = iroha.protocol.BlockOuterClass.Block
          .parseFrom(delivery.getBody());
      logger.info(
          "New Iroha block arrived. Height " + block.getBlockV1()
              .getPayload().getHeight());
      return block;
    });

  }
}
