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
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import java.io.Closeable;
import java.io.IOException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
  private final KeyPair brvsKeyPair;
  private final String brvsAccountId;
  private final KeyPair userKeyPair;
  private final Connection connection;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

  public IrohaReliableChainListener(
      IrohaAPI irohaAPI,
      String brvsAccountId,
      KeyPair brvsKeyPair,
      KeyPair userKeyPair,
      String rmqHost,
      int rmqPort) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(brvsAccountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null nor empty");
    }
    Objects.requireNonNull(brvsKeyPair, "Brvs Keypair must not be null");
    Objects.requireNonNull(userKeyPair, "User Keypair must not be null");
    if (Strings.isNullOrEmpty(rmqHost)) {
      throw new IllegalArgumentException("RMQ host must not be neither null nor empty");
    }
    if (rmqPort < 1 || rmqPort > 65535) {
      throw new IllegalArgumentException("RMQ port must be valid");
    }

    this.brvsAccountId = brvsAccountId;
    this.irohaAPI = irohaAPI;
    this.brvsKeyPair = brvsKeyPair;
    this.userKeyPair = userKeyPair;

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
  public Set<TransactionBatch> getAllPendingTransactions(Iterable<String> accountsToMonitor) {
    Set<TransactionBatch> pendingTransactions = new HashSet<>(
        getPendingTransactions(brvsAccountId, brvsKeyPair)
    );
    accountsToMonitor.forEach(account ->
        pendingTransactions.addAll(getPendingTransactions(account, userKeyPair))
    );
    return pendingTransactions;
  }

  /**
   * Queries pending transactions for a specified account and keypair
   *
   * @param accountId user that transactions should be queried for
   * @param keyPair user keypair
   * @return list of user transactions that are in pending state
   */
  private List<TransactionBatch> getPendingTransactions(String accountId, KeyPair keyPair) {
    Queries.Query query = Query.builder(accountId, 1)
        .getPendingTransactions()
        .buildSigned(keyPair);
    return constructBatches(
        irohaAPI.query(query)
            .getTransactionsResponse()
            .getTransactionsList()
    );
  }

  /**
   * Converts Iroha transactions to brvs batches
   *
   * @param transactions transaction list to be converted
   * @return {@link List} of {@link TransactionBatch} of input
   */
  private List<TransactionBatch> constructBatches(List<Transaction> transactions) {
    List<TransactionBatch> transactionBatches = new ArrayList<>();
    for (int i = 0; i < transactions.size(); ) {
      final List<Transaction> transactionListForBatch = new ArrayList<>();
      final int hashesCount = transactions
          .get(i)
          .getPayload()
          .getBatch()
          .getReducedHashesCount();
      final int toInclude = hashesCount == 0 ? 1 : hashesCount;

      for (int j = 0; j < toInclude; j++) {
        transactionListForBatch.add(transactions.get(i + j));
      }
      i += toInclude;
      transactionBatches.add(new TransactionBatch(transactionListForBatch));
    }
    return transactionBatches;
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
