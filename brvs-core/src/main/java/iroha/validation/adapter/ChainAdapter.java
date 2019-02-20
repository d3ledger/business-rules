package iroha.validation.adapter;


import com.google.common.base.Strings;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Queries;
import java.io.IOException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import jp.co.soramitsu.iroha.java.BlocksQueryBuilder;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ChainAdapter {

  private static final Logger logger = LoggerFactory.getLogger(ChainAdapter.class);

  private final IrohaAPI irohaAPI;
  // BRVS account id to query Iroha
  private final String accountId;
  // BRVS keypair to query Iroha
  private final KeyPair keyPair;

  private final String RMQHost;
  private final Integer RMQPort;

  @Autowired
  public ChainAdapter(IrohaAPI irohaAPI,
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

  public void run() throws IOException, TimeoutException {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RMQHost);
    factory.setPort(RMQPort);
    Connection conn = factory.newConnection();
    Channel ch = conn.createChannel();
    ch.exchangeDeclare("iroha", "fanout", true);
    logger.info("Listening Iroha blocks");

    Queries.BlocksQuery query = new BlocksQueryBuilder(accountId, Instant.now(), 1)
        .buildSigned(keyPair);
    Observable<Block> obs = irohaAPI.blocksQuery(query).map(response -> {
          logger.info(
              "New Iroha block arrived. Height " + response.getBlockResponse().getBlock().getBlockV1()
                  .getPayload().getHeight());
          return response.getBlockResponse().getBlock();
        }
    );

    obs.blockingSubscribe(block -> {
      ch.basicPublish("iroha", "", MessageProperties.MINIMAL_PERSISTENT_BASIC, block.toByteArray());
      logger.info("Block pushed");
    });
  }
}

