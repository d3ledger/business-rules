package iroha.validation.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import iroha.protocol.BlockOuterClass;
import iroha.protocol.Primitive.RolePermission;
import iroha.protocol.QryResponses.Account;
import iroha.protocol.QryResponses.AccountAsset;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.config.ValidationServiceContext;
import iroha.validation.listener.IrohaReliableChainListener;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.rules.impl.TransferTxVolumeRule;
import iroha.validation.service.ValidationService;
import iroha.validation.service.impl.ValidationServiceImpl;
import iroha.validation.transactions.provider.impl.BasicTransactionProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.signatory.impl.TransactionSignerImpl;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.transactions.storage.impl.dummy.DummyMemoryTransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.validators.impl.SimpleAggregationValidator;
import iroha.validation.verdict.Verdict;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
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
import org.testcontainers.containers.GenericContainer;

class IrohaIntegrationTest {

  private static final Ed25519Sha3 crypto = new Ed25519Sha3();
  private static final KeyPair peerKeypair = crypto.generateKeypair();
  private static final KeyPair receiverKeypair = crypto.generateKeypair();
  private static final KeyPair validatorKeypair = crypto.generateKeypair();
  private static final KeyPair senderKeypair = crypto.generateKeypair();
  private static final String domainName = "notary";
  private static final String roleName = "user";
  private static final String receiverName = "test";
  private static final String senderName = "richguy";
  private static final String receiverId = String.format("%s@%s", receiverName, domainName);
  private static final String senderId = String.format("%s@%s", senderName, domainName);
  private static final String asset = "bux";
  private static final String assetId = String.format("%s#%s", asset, domainName);
  private static final String initialReceiverAmount = "10";
  private static final int TRANSACTION_VALIDATION_TIMEOUT = 5000;
  private static final int TRANSACTION_REACTION_TIMEOUT = 500;
  private CacheProvider cacheProvider;
  private final TransactionVerdictStorage transactionVerdictStorage = new DummyMemoryTransactionVerdictStorage();
  private static final GenericContainer rmq = new GenericContainer<>("rabbitmq:3-management")
      .withExposedPorts(15672, 5672);

  private IrohaContainer iroha;
  private IrohaAPI irohaAPI;
  private String RMQHost;
  private Integer RMQPort;

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
                        RolePermission.can_add_signatory,
                        RolePermission.can_create_account,
                        RolePermission.can_get_all_signatories,
                        RolePermission.can_get_all_accounts,
                        RolePermission.can_get_all_txs,
                        RolePermission.can_get_blocks,
                        RolePermission.can_transfer,
                        RolePermission.can_receive,
                        RolePermission.can_add_asset_qty,
                        RolePermission.can_get_all_acc_ast
                    )
                )
                .createDomain(domainName, roleName)
                // create receiver acc
                .createAccount(receiverName, domainName, receiverKeypair.getPublic())
                // create sender acc
                .createAccount(senderName, domainName, senderKeypair.getPublic())
                // allow validator to sign receiver tx
                .addSignatory(receiverId, validatorKeypair.getPublic())
                // allow validator to sign sender tx
                .addSignatory(senderId, validatorKeypair.getPublic())
                .createAsset(asset, domainName, 0)
                // transactions in genesis block can be unsigned
                .build()
                .build()
        )
        .addTransaction(
            // add some assets to receiver acc
            Transaction.builder(receiverId)
                .addAssetQuantity(assetId, initialReceiverAmount)
                .sign(receiverKeypair)
                .build()
        )
        .addTransaction(
            // add some assets to sender acc
            Transaction.builder(senderId)
                .addAssetQuantity(assetId, "1000000")
                .sign(senderKeypair)
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

  private ValidationService getService(IrohaAPI irohaAPI, String accountId,
      KeyPair keyPair) {
    return new ValidationServiceImpl(new ValidationServiceContext(
        Collections.singletonList(new SimpleAggregationValidator(Arrays.asList(
            new SampleRule(),
            new TransferTxVolumeRule(assetId, new BigDecimal(150))
            ))
        ),
        new BasicTransactionProvider(
            transactionVerdictStorage,
            cacheProvider,
            new IrohaReliableChainListener(irohaAPI, accountId, keyPair, RMQHost, RMQPort)
        ),
        new TransactionSignerImpl(
            irohaAPI,
            keyPair,
            transactionVerdictStorage
        )
    ));
  }

  @BeforeEach
  void setUp() {
    iroha = new IrohaContainer()
        .withPeerConfig(getPeerConfig());

    iroha.start();
    irohaAPI = iroha.getApi();

    cacheProvider = new CacheProvider();

    rmq.start();
    RMQHost = rmq.getContainerIpAddress();
    RMQPort = rmq.getMappedPort(5672);
  }

  @AfterEach
  void tearDown() {
    irohaAPI.close();
    iroha.close();
    rmq.stop();
  }

  /**
   * @given {@link ValidationService} instance with {@link TransferTxVolumeRule} that limits asset
   * amount to 150 for the asset called "bux#notary"
   * @when {@link Transaction} with {@link iroha.protocol.Commands.Command CreateAccount} command
   * for "abcd@notary" is sent to Iroha peer
   * @then {@link TransferTxVolumeRule} is satisfied by such {@link Transaction} and tx is signed by
   * BRVS and committed in Iroha so account "abcd@notary" exists in Iroha
   */
  @Test
  void createAccountTransactionOnTransferLimitValidatorTest() throws InterruptedException {
    // construct BRVS using some account for block streaming and validator keypair
    ValidationService validationService = getService(irohaAPI, receiverId, validatorKeypair);
    // register accounts to monitor transactions of
    validationService.registerAccount(receiverId);
    validationService.registerAccount(senderId);
    // subscribe to new transactions
    validationService.verifyTransactions();

    // send create account transaction to check rules
    String newAccountName = "abcd";
    TransactionOuterClass.Transaction transaction = Transaction.builder(receiverId)
        .createAccount(
            newAccountName,
            domainName,
            crypto.generateKeypair().getPublic()
        )
        .setQuorum(2)
        .sign(receiverKeypair).build();
    irohaAPI.transactionSync(transaction);

    Thread.sleep(TRANSACTION_REACTION_TIMEOUT);
    // Check account is not blocked
    assertNull(cacheProvider.getAccountBlockedBy(ValidationUtils.hexHash(transaction)));

    Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);

    assertEquals(Verdict.VALIDATED, transactionVerdictStorage
        .getTransactionVerdict(ValidationUtils.hexHash(transaction)).getStatus());

    // query Iroha and check
    String newAccountId = String.format("%s@%s", newAccountName, domainName);
    Account accountResponse = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccount(newAccountId)
        .buildSigned(receiverKeypair))
        .getAccountResponse()
        .getAccount();
    assertEquals(newAccountId, accountResponse.getAccountId());
    assertEquals(domainName, accountResponse.getDomainId());
    assertEquals(1, accountResponse.getQuorum());
  }

  /**
   * @given {@link ValidationService} instance with {@link TransferTxVolumeRule} that limits asset
   * amount to 150 for the asset called "bux#notary"
   * @when {@link Transaction} with {@link iroha.protocol.Commands.Command TransferAsset} command of
   * 100 "bux#notary" from "sender@notary" to "receiver@notary" sent to Iroha peer
   * @then {@link TransferTxVolumeRule} is satisfied by such {@link Transaction} and tx is signed by
   * BRVS and committed in Iroha so destination account balance is increased by 100 "bux#notary"
   */
  @Test
  void validTransferAssetOnTransferLimitValidatorTest() throws InterruptedException {
    // construct BRVS using some account for block streaming and validator keypair
    ValidationService validationService = getService(irohaAPI, receiverId, validatorKeypair);
    // register accounts to monitor transactions of
    validationService.registerAccount(receiverId);
    validationService.registerAccount(senderId);
    // subscribe to new transactions
    validationService.verifyTransactions();

    // send valid transfer asset transaction
    TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", "100")
        .setQuorum(2)
        .sign(senderKeypair).build();
    irohaAPI.transactionSync(transaction);

    Thread.sleep(TRANSACTION_REACTION_TIMEOUT);
    // Check account is blocked
    assertEquals(senderId, cacheProvider.getAccountBlockedBy(ValidationUtils.hexHash(transaction)));

    Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);

    assertEquals(Verdict.VALIDATED, transactionVerdictStorage
        .getTransactionVerdict(ValidationUtils.hexHash(transaction)).getStatus());

    // query Iroha and check that transfer was committed
    AccountAsset accountAsset = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccountAssets(receiverId)
        .buildSigned(receiverKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().get(0);
    assertEquals(assetId, accountAsset.getAssetId());
    assertEquals("110", accountAsset.getBalance());
  }

  /**
   * @given {@link ValidationService} instance with {@link TransferTxVolumeRule} that limits asset
   * amount to 150 for the asset called "bux#notary"
   * @when {@link Transaction} with {@link iroha.protocol.Commands.Command TransferAsset} command of
   * 200 "bux#notary" from "sender@notary" to "receiver@notary" sent to Iroha peer
   * @then {@link TransferTxVolumeRule} is NOT satisfied by such {@link Transaction} due to asset
   * limit violation and tx is rejected by BRVS and failed in Iroha so destination account balance
   * is the same as before the trans attempt
   */
  @Test
  void invalidTransferAssetOnTransferLimitValidatorTest() throws InterruptedException {
    // construct BRVS using some account for block streaming and validator keypair
    ValidationService validationService = getService(irohaAPI, receiverId, validatorKeypair);
    // register accounts to monitor transactions of
    validationService.registerAccount(receiverId);
    validationService.registerAccount(senderId);
    // subscribe to new transactions
    validationService.verifyTransactions();

    // send invalid transfer asset transaction
    TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test invalid transfer", "200")
        .setQuorum(2)
        .sign(senderKeypair).build();
    irohaAPI.transactionSync(transaction);

    Thread.sleep(TRANSACTION_REACTION_TIMEOUT);
    // Check account is not blocked
    assertNull(cacheProvider.getAccountBlockedBy(ValidationUtils.hexHash(transaction)));

    Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);

    assertEquals(Verdict.REJECTED, transactionVerdictStorage
        .getTransactionVerdict(ValidationUtils.hexHash(transaction)).getStatus());

    // query Iroha and check that transfer was not committed
    AccountAsset accountAsset = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccountAssets(receiverId)
        .buildSigned(receiverKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().get(0);
    assertEquals(assetId, accountAsset.getAssetId());
    assertEquals(initialReceiverAmount, accountAsset.getBalance());
  }

  /**
   * @given {@link IrohaReliableChainListener} instance running
   * @when two {@link Transaction} with {@link iroha.protocol.Commands.Command AddAssetQuantity}
   * commands for "test@notary" is sent to Iroha peer
   * @then two {@link BlockOuterClass} arrive
   */
  @Test
  void irohaReliableChainListenerTest() throws InterruptedException, IOException, TimeoutException {
    IrohaReliableChainListener listener = new IrohaReliableChainListener(
        irohaAPI,
        senderId,
        senderKeypair,
        RMQHost,
        RMQPort
    );

    AtomicInteger blocks_n = new AtomicInteger(0);
    listener.getBlockStreaming().subscribe(block -> blocks_n.incrementAndGet());

    for (int i = 0; i < 2; i++) {
      irohaAPI.transactionSync(Transaction.builder(senderId)
          .addAssetQuantity(assetId, "1")
          .sign(senderKeypair).build()
      );

      Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);
    }

    // query Iroha and check
    String balance = irohaAPI.query(new QueryBuilder(senderId, Instant.now(), 1)
        .getAccountAssets(senderId)
        .buildSigned(senderKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList()
        .get(0)
        .getBalance();

    assertEquals("1000002", balance);
    assertEquals(2, blocks_n.get());
    listener.close();
  }
}
