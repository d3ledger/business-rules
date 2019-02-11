package iroha.validation.behavior;

import iroha.protocol.BlockOuterClass;
import iroha.protocol.Primitive.RolePermission;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.config.ValidationServiceContext;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.service.ValidationService;
import iroha.validation.service.impl.ValidationServiceImpl;
import iroha.validation.transactions.provider.impl.BasicTransactionProvider;
import iroha.validation.transactions.signatory.impl.TransactionSignerImpl;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.transactions.storage.impl.DummyMemoryTransactionVerdictStorage;
import iroha.validation.validators.impl.SampleValidator;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryBuilder;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer;
import jp.co.soramitsu.iroha.testcontainers.PeerConfig;
import jp.co.soramitsu.iroha.testcontainers.detail.GenesisBlockBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.shaded.org.apache.commons.lang.RandomStringUtils;

public class IrohaIntegrationTest {

  private static final Ed25519Sha3 crypto = new Ed25519Sha3();
  private static final KeyPair peerKeypair = crypto.generateKeypair();
  private static final KeyPair firstUserKeypair = crypto.generateKeypair();
  private static final KeyPair validatorUserKeypair = Utils.parseHexKeypair(
      "092e71b031a51adae924f7cd944f0371ae8b8502469e32693885334dedcc6001",
      "e51123b78d658418d018e7d2486021209af3cff82714b4cb7925870fec6097dc"
  );
  private static final KeyPair serviceUserKeypair = crypto.generateKeypair();
  private static final String domainName = "notary";
  private static final String roleName = "user";
  private static final String userName = "test";
  private static final String serviceUserName = "richguy";
  private static final String userId = String.format("%s@%s", userName, domainName);
  private static final String serviceUserId = String.format("%s@%s", serviceUserName, domainName);
  private static final String asset = "bux";
  private static final String assetId = String.format("%s#%s", asset, domainName);

  private IrohaContainer iroha;
  private IrohaAPI irohaAPI;
  private ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(2);

  @Autowired
  private ValidationService validationService;

  private static BlockOuterClass.Block getGenesisBlock() {
    return new GenesisBlockBuilder()
        // first transaction
        .addTransaction(
            // transactions in genesis block can have no creator
            Transaction.builder(null)
                // by default peer is listening on port 10001
                .addPeer("0.0.0.0:10001", peerKeypair.getPublic())
                // create default "user" role
                .createRole(roleName,
                    Arrays.asList(
                        RolePermission.can_add_signatory,
                        RolePermission.can_create_account,
                        RolePermission.can_set_quorum,
                        RolePermission.can_get_all_signatories,
                        RolePermission.can_get_all_txs,
                        RolePermission.can_get_blocks,
                        RolePermission.can_transfer,
                        RolePermission.can_receive,
                        RolePermission.can_add_asset_qty,
                        RolePermission.can_get_all_acc_ast
                    )
                )
                .createDomain(domainName, roleName)
                // create user
                .createAccount(userName, domainName, firstUserKeypair.getPublic())
                .createAccount(serviceUserName, domainName, serviceUserKeypair.getPublic())
                .addSignatory(userId, validatorUserKeypair.getPublic())
                .addSignatory(serviceUserId, validatorUserKeypair.getPublic())
                .setAccountQuorum(userId, 1)
                .setAccountQuorum(serviceUserId, 1)
                .createAsset(asset, domainName, 0)
                // transactions in genesis block can be unsigned
                .build()
                .build()
        )
        .addTransaction(
            Transaction.builder(serviceUserId)
                .addAssetQuantity(assetId, "10000")
                .sign(serviceUserKeypair)
                .build()
        ).build();
  }

  private static PeerConfig getPeerConfig() {
    PeerConfig config = PeerConfig.builder()
        .genesisBlock(getGenesisBlock())
        .build();

    // don't forget to add peer keypair to config
    config.withPeerKeyPair(peerKeypair);

    return config;
  }

  private static void spamPendingCreateAccTx(IrohaAPI api) {
    TransactionOuterClass.Transaction transaction = Transaction.builder(userId)
        .createAccount(
            RandomStringUtils.random(9, "abcdefghijklmnoprstvwxyz"),
            domainName,
            crypto.generateKeypair().getPublic()
        )
        .setQuorum(2)
        .sign(firstUserKeypair).build();
    api.transactionSync(transaction);
  }

  private static void spamPendingTransferTx(IrohaAPI api) {
    TransactionOuterClass.Transaction transaction = Transaction.builder(serviceUserId)
        .transferAsset(serviceUserId, userId, assetId, "test transfer", "1")
        .setQuorum(2)
        .sign(serviceUserKeypair).build();
    api.transactionSync(transaction);
  }

  public static ValidationService getService(IrohaAPI irohaAPI, String accountId, KeyPair keyPair) {
    TransactionVerdictStorage transactionVerdictStorage = new DummyMemoryTransactionVerdictStorage();
    return new ValidationServiceImpl(new ValidationServiceContext(
        Collections.singletonList(new SampleValidator(Collections.singletonList(new SampleRule()))),
        new BasicTransactionProvider(irohaAPI, accountId, keyPair, transactionVerdictStorage),
        new TransactionSignerImpl(irohaAPI, keyPair, transactionVerdictStorage)
    ));
  }

  @BeforeEach
  public void setUp() {
    iroha = new IrohaContainer()
        .withPeerConfig(getPeerConfig());

    iroha.start();

    irohaAPI = iroha.getApi();

    threadPool.scheduleAtFixedRate(() -> spamPendingCreateAccTx(irohaAPI), 0, 3, TimeUnit.SECONDS);
    threadPool.scheduleAtFixedRate(() -> spamPendingTransferTx(irohaAPI), 0, 1, TimeUnit.SECONDS);
  }

  @AfterEach
  public void tearDown() {
    threadPool.shutdownNow();
    irohaAPI.close();
    iroha.close();
  }

  /**
   * Test launches full pipeline for 15 seconds
   */
  @Test
  public void validatorTest() throws InterruptedException {
    ValidationService validationService = getService(irohaAPI, userId, validatorUserKeypair);
    validationService.registerAccount(userId);
    validationService.registerAccount(serviceUserId);
    validationService.verifyTransactions();
    irohaAPI.query(new QueryBuilder(serviceUserId, Instant.now(), 1)
        .getAccountAssets(serviceUserId)
        .buildSigned(serviceUserKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().forEach(System.out::println);
    Thread.sleep(15000);
    irohaAPI.query(new QueryBuilder(userId, Instant.now(), 1)
        .getAccountAssets(userId)
        .buildSigned(firstUserKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().forEach(System.out::println);
  }
}
