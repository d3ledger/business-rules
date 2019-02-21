package iroha.validation.listener;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.Runnables;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.MessageProperties;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.BlockOuterClass;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.QryResponses;
import iroha.protocol.Queries;
import iroha.protocol.TransactionOuterClass;
import iroha.protocol.TransactionOuterClass.Transaction;
import java.io.Closeable;
import java.io.IOException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jp.co.soramitsu.iroha.java.BlocksQueryBuilder;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class IrohaReliableChainListener implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(IrohaReliableChainListener.class);

  private final IrohaAPI irohaAPI;
  // BRVS account id to query Iroha
  private final String accountId;
  // BRVS keypair to query Iroha
  private final KeyPair keyPair;

  private static final String EXCHANGE_RMQ_NAME = "iroha";

  private Connection connection;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

  private boolean isListening;

  @Autowired
  public IrohaReliableChainListener(
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
    if (Strings.isNullOrEmpty(RMQHost)) {
      throw new IllegalArgumentException("RMQ host must not be neither null or empty");
    }
    if (RMQPort < 1) {
      throw new IllegalArgumentException("RMQ port must be valid");
    }

    this.irohaAPI = irohaAPI;
    this.accountId = accountId;
    this.keyPair = keyPair;

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RMQHost);
    factory.setPort(RMQPort);
    try {
      connection = factory.newConnection();
    } catch (TimeoutException | IOException e) {
      throw new IllegalStateException("Cannot acquire MQ connection", e);
    }
  }


  /**
   * Queries pending transactions for specific users
   *
   * @param accountsToMonitor users that transactions should be queried for
   * @return set of transactions that are in pending state
   */
  public Set<Transaction> getAllPendingTransactions(Set<String> accountsToMonitor) {
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
   * Method providing new blocks coming from Iroha by reading MQ
   *
   * @return {@link Observable} of Iroha proto {@link QryResponses.BlockQueryResponse} block
   */
  public synchronized Observable<BlockOuterClass.Block> getBlockStreaming() {
    if (!isListening) {
      executorService.schedule(this::pushIrohaBlocksToMQ, 0, TimeUnit.SECONDS);
      isListening = true;
    }

    PublishSubject<Delivery> source = PublishSubject.create();
    DeliverCallback deliverCallback = (consumerTag, delivery) -> source.onNext(delivery);
    CancelCallback cancelCallback = consumerTag -> Runnables.doNothing().run();
    // start consumer
    try {
      Channel channel = connection.createChannel();
      channel.exchangeDeclare(EXCHANGE_RMQ_NAME, BuiltinExchangeType.FANOUT, true);
      String queue = channel.queueDeclare("", true, false, false, null).getQueue();
      channel.queueBind(queue, EXCHANGE_RMQ_NAME, "");
      channel.basicConsume(queue, true, deliverCallback, cancelCallback);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot consume Iroha block", e);
    }

    logger.info("Subscribed to Iroha chain listener");

    return source.map(delivery -> {
          BlockOuterClass.Block block = iroha.protocol.BlockOuterClass.Block
              .parseFrom(delivery.getBody());
          logger.info("Iroha block consumed. Height " + block.getBlockV1().getPayload().getHeight());
          return block;
        }
    );
  }

  /**
   * Method pushing new blocks coming from Iroha into MQ
   */
  private void pushIrohaBlocksToMQ() {
    logger.info("Listening Iroha blocks");
    Queries.BlocksQuery query = new BlocksQueryBuilder(accountId, Instant.now(), 1)
        .buildSigned(keyPair);
    Observable<Block> irohaBlocksObservable = irohaAPI.blocksQuery(query)
        .map(response -> response.getBlockResponse().getBlock());

    Channel channel;
    try { channel = connection.createChannel();
    channel.exchangeDeclare(EXCHANGE_RMQ_NAME, BuiltinExchangeType.FANOUT, true);
    String queue = channel.queueDeclare("", true, false, false, null).getQueue();
    channel.queueBind(queue, EXCHANGE_RMQ_NAME, "");
    } catch (IOException e) {
      throw new IllegalStateException("Cannot initialize MQ Iroha block producer", e);
    }

    irohaBlocksObservable.blockingSubscribe(block -> {
      channel.basicPublish(
          EXCHANGE_RMQ_NAME,
          "",
          MessageProperties.MINIMAL_PERSISTENT_BASIC,
          block.toByteArray()
      );
      logger.info("New Block pushed to MQ. Height " + block.getBlockV1().getPayload().getHeight());
    });
  }

  @Override
  public void close() throws IOException {
    executorService.shutdownNow();
    connection.close();
  }
}
