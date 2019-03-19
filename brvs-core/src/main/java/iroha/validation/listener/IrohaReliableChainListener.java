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
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.BlockOuterClass;
import iroha.protocol.QryResponses;
import iroha.protocol.Queries;
import iroha.protocol.TransactionOuterClass;
import iroha.protocol.TransactionOuterClass.Transaction;
import java.io.Closeable;
import java.io.IOException;
import java.security.KeyPair;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IrohaReliableChainListener implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(IrohaReliableChainListener.class);
  private static final String EXCHANGE_RMQ_NAME = "iroha";
  private static final String BRVS_QUEUE_RMQ_NAME = "brvs";
  private static final int IROHA_WORLD_STATE_UPDATE_TIME = 200;


  private final IrohaAPI irohaAPI;
  // BRVS keypair to query Iroha
  private final KeyPair keyPair;
  private final Connection connection;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

  public IrohaReliableChainListener(
      IrohaAPI irohaAPI,
      String accountId,
      KeyPair keyPair,
      String rmqHost,
      int rmqPort) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(accountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null nor empty");
    }
    Objects.requireNonNull(keyPair, "Keypair must not be null");
    if (Strings.isNullOrEmpty(rmqHost)) {
      throw new IllegalArgumentException("RMQ host must not be neither null nor empty");
    }
    if (rmqPort < 1 || rmqPort > 65535) {
      throw new IllegalArgumentException("RMQ port must be valid");
    }

    this.irohaAPI = irohaAPI;
    this.keyPair = keyPair;

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rmqHost);
    factory.setPort(rmqPort);
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
  public Set<Transaction> getAllPendingTransactions(Iterable<String> accountsToMonitor) {
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
    PublishSubject<Delivery> source = PublishSubject.create();
    DeliverCallback deliverCallback = (consumerTag, delivery) -> source.onNext(delivery);
    CancelCallback cancelCallback = consumerTag -> Runnables.doNothing().run();
    // start consumer
    try {
      Channel channel = connection.createChannel();
      channel.exchangeDeclare(EXCHANGE_RMQ_NAME, BuiltinExchangeType.FANOUT, true);
      String queue = channel.queueDeclare(BRVS_QUEUE_RMQ_NAME, true, false, false, null).getQueue();
      channel.queueBind(queue, EXCHANGE_RMQ_NAME, "");
      channel.basicConsume(queue, true, deliverCallback, cancelCallback);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot consume Iroha block", e);
    }

    logger.info("Subscribed to Iroha chain listener");

    return source.map(delivery -> {
          // Iroha state is not updated immediately after block arrival
          // In order to query a relevant state there is a delay
          // https://jira.hyperledger.org/browse/IR-406
          Thread.sleep(IROHA_WORLD_STATE_UPDATE_TIME);
          BlockOuterClass.Block block = iroha.protocol.BlockOuterClass.Block
              .parseFrom(delivery.getBody());
          logger.info("Iroha block consumed. Height " + block.getBlockV1().getPayload().getHeight());
          return block;
        }
    );
  }

  @Override
  public void close() throws IOException {
    executorService.shutdownNow();
    connection.close();
  }
}
