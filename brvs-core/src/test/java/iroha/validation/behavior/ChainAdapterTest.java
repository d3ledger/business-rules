package iroha.validation.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;

import iroha.protocol.BlockOuterClass;
import iroha.protocol.Primitive.RolePermission;
import iroha.validation.adapter.ChainAdapter;
import iroha.validation.transactions.provider.impl.IrohaHelper;
import java.io.IOException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryBuilder;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer;
import jp.co.soramitsu.iroha.testcontainers.PeerConfig;
import jp.co.soramitsu.iroha.testcontainers.detail.GenesisBlockBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

public class ChainAdapterTest {

  private static final Logger logger = LoggerFactory.getLogger(ChainAdapterTest.class);

  private static final Ed25519Sha3 crypto = new Ed25519Sha3();
  private static final KeyPair peerKeypair = crypto.generateKeypair();
  private static final KeyPair accountKeypair = crypto.generateKeypair();
  private static final String domainName = "notary";
  private static final String roleName = "user";
  private static final String accountName = "test";

  private static final String accountId = String.format("%s@%s", accountName, domainName);
  private static final String asset = "bux";
  private static final String assetId = String.format("%s#%s", asset, domainName);
  private static final int TRANSACTION_VALIDATION_TIMEOUT = 5000;


  private static final GenericContainer rmq = new GenericContainer<>("rabbitmq:3-management")
      .withExposedPorts(15672, 5672);

  private String RMQHost;
  private Integer RMQPort;

  private IrohaContainer iroha;
  private IrohaAPI irohaAPI;

  private static BlockOuterClass.Block getGenesisBlock() {
    return new GenesisBlockBuilder()
        // first transaction
        .addTransaction(
            // transactions in genesis block can have no creator
            Transaction.builder(null)
                // by default peer is listening on port 10001
                .addPeer("0.0.0.0:10001", peerKeypair.getPublic())
                // create default role
                .createRole(roleName,
                    Arrays.asList(
                        RolePermission.can_add_asset_qty,
                        RolePermission.can_get_all_acc_ast,
                        RolePermission.can_get_blocks
                    )
                )
                .createDomain(domainName, roleName)
                // create receiver acc
                .createAccount(accountName, domainName, accountKeypair.getPublic())
                .createAsset(asset, domainName, 0)
                // transactions in genesis block can be unsigned
                .build()
                .build()
        )
        .build();
  }

  private static PeerConfig getPeerConfig() {
    PeerConfig config = PeerConfig.builder()
        .genesisBlock(getGenesisBlock())
        .build();

    // don't forget to add peer keypair to config
    config.withPeerKeyPair(peerKeypair);

    return config;
  }

  @BeforeEach
  void setUp() {
    iroha = new IrohaContainer()
        .withPeerConfig(getPeerConfig());

    iroha.start();
    rmq.start();
    this.RMQHost = rmq.getContainerIpAddress();
    this.RMQPort = rmq.getMappedPort(5672);

    irohaAPI = iroha.getApi();
  }

  @AfterEach
  void tearDown() {
    irohaAPI.close();
    iroha.close();
    rmq.stop();
  }


  /**
   * @given {@link ChainAdapter} instance running and {@link IrohaHelper}
   * @when two {@link Transaction} with {@link iroha.protocol.Commands.Command AddAssetQuantity}
   * command for "test@notary" is sent to Iroha peer
   * @then two {@link BlockOuterClass} arrive
   */
  @Test
  void createAccountTransactionOnTransferLimitValidatorTest() throws InterruptedException {

    ChainAdapter chainAdapter = new ChainAdapter(irohaAPI, accountId, accountKeypair, RMQHost,
        RMQPort);
    Thread chainAdapterThread = new Thread() {
      public void run() {
        try {
          chainAdapter.run();
        } catch (IOException e) {
          e.printStackTrace();
        } catch (TimeoutException e) {
          e.printStackTrace();
        }
      }
    };
    chainAdapterThread.start();
    Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);

    IrohaHelper helper = new IrohaHelper(irohaAPI, accountId, accountKeypair, RMQHost, RMQPort);

    AtomicInteger blocks_n = new AtomicInteger(0);
    helper.getBlockStreaming().subscribe(block -> {
      blocks_n.incrementAndGet();
      System.out.println(block.getBlockV1().getPayload().getHeight());
    });

    for (int i = 0; i < 2; i++) {
      irohaAPI.transactionSync(Transaction.builder(accountId)
          .addAssetQuantity(assetId, "1")
          .sign(accountKeypair).build());

      Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);
    }

    // query Iroha and check
    String balance = irohaAPI.query(new QueryBuilder(accountId, Instant.now(), 1)
        .getAccountAssets(accountId)
        .buildSigned(accountKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList()
        .get(0)
        .getBalance();
    assertEquals("2", balance);
    assertEquals(2, blocks_n.get());
  }

}
