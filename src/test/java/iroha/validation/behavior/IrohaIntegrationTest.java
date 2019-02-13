package iroha.validation.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;

import iroha.protocol.BlockOuterClass;
import iroha.protocol.Primitive.RolePermission;
import iroha.protocol.QryResponses.Account;
import iroha.protocol.QryResponses.AccountAsset;
import iroha.validation.config.ValidationServiceContext;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.rules.impl.TransferTxVolumeRule;
import iroha.validation.service.ValidationService;
import iroha.validation.service.impl.ValidationServiceImpl;
import iroha.validation.transactions.provider.impl.BasicTransactionProvider;
import iroha.validation.transactions.provider.impl.IrohaHelper;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.signatory.impl.TransactionSignerImpl;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.transactions.storage.impl.DummyMemoryTransactionVerdictStorage;
import iroha.validation.validators.impl.SimpleAggregationValidator;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
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
  private static final int TRANSACTION_VALIDATION_TIMEOUT = 5000;


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
            // add some assets to sender acc
            Transaction.builder(senderId)
                .addAssetQuantity(assetId, "1000000")
                .sign(senderKeypair)
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

  private static ValidationService getService(IrohaAPI irohaAPI, String accountId,
      KeyPair keyPair) {
    TransactionVerdictStorage transactionVerdictStorage = new DummyMemoryTransactionVerdictStorage();
    return new ValidationServiceImpl(new ValidationServiceContext(
        Collections.singletonList(new SimpleAggregationValidator(Arrays.asList(
            new SampleRule(),
            new TransferTxVolumeRule(assetId, new BigDecimal(150))
            ))
        ),
        new BasicTransactionProvider(
            transactionVerdictStorage,
            new CacheProvider(),
            new IrohaHelper(irohaAPI, accountId, keyPair)
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
  }

  @AfterEach
  void tearDown() {
    irohaAPI.close();
    iroha.close();
  }

  /**
   * Test launches full pipeline
   */
  @Test
  void validatorTest() throws InterruptedException {
    // construct BRVS using some account for block streaming and validator keypair
    ValidationService validationService = getService(irohaAPI, receiverId, validatorKeypair);
    // register accounts to monitor transactions of
    validationService.registerAccount(receiverId);
    validationService.registerAccount(senderId);
    // subscribe to new transactions
    validationService.verifyTransactions();

    // send create account transaction to check rules
    String newAccountName = "abcd";
    irohaAPI.transactionSync(Transaction.builder(receiverId)
        .createAccount(
            newAccountName,
            domainName,
            crypto.generateKeypair().getPublic()
        )
        .setQuorum(2)
        .sign(receiverKeypair).build());
    Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);

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

    // send valid transfer asset transaction
    irohaAPI.transactionSync(Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", "100")
        .setQuorum(2)
        .sign(senderKeypair).build());
    Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);

    // send invalid transfer asset transaction
    irohaAPI.transactionSync(Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test invalid transfer", "200")
        .setQuorum(2)
        .sign(senderKeypair).build());
    Thread.sleep(TRANSACTION_VALIDATION_TIMEOUT);

    // query Iroha and check that only first transfer was committed
    AccountAsset accountAsset = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccountAssets(receiverId)
        .buildSigned(receiverKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().get(0);
    assertEquals(assetId, accountAsset.getAssetId());
    assertEquals("100", accountAsset.getBalance());
  }
}
