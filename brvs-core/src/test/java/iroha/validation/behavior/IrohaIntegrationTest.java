/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.d3.commons.config.RMQConfig;
import iroha.protocol.BlockOuterClass;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.Primitive.GrantablePermission;
import iroha.protocol.Primitive.RolePermission;
import iroha.protocol.QryResponses.Account;
import iroha.protocol.QryResponses.AccountAsset;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.config.ValidationServiceContext;
import iroha.validation.listener.BrvsIrohaChainListener;
import iroha.validation.rules.Rule;
import iroha.validation.rules.RuleMonitor;
import iroha.validation.rules.impl.assets.TransferTxVolumeRule;
import iroha.validation.rules.impl.core.SampleRule;
import iroha.validation.service.ValidationService;
import iroha.validation.service.impl.ValidationServiceImpl;
import iroha.validation.transactions.provider.impl.AccountManager;
import iroha.validation.transactions.provider.impl.BasicTransactionProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.signatory.impl.TransactionSignerImpl;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.transactions.storage.impl.mongo.MongoBlockStorage;
import iroha.validation.transactions.storage.impl.mongo.MongoTransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.validators.impl.SimpleAggregationValidator;
import iroha.validation.verdict.Verdict;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.QueryBuilder;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.java.subscription.WaitForTerminalStatus;
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer;
import jp.co.soramitsu.iroha.testcontainers.PeerConfig;
import jp.co.soramitsu.iroha.testcontainers.detail.GenesisBlockBuilder;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.containers.GenericContainer;

@TestInstance(Lifecycle.PER_CLASS)
class IrohaIntegrationTest {

  private static final Ed25519Sha3 crypto = new Ed25519Sha3();
  private static final KeyPair peerKeypair = crypto.generateKeypair();
  private static final KeyPair senderKeypair = crypto.generateKeypair();
  private static final KeyPair receiverKeypair = crypto.generateKeypair();
  private static final KeyPair validatorKeypair = crypto.generateKeypair();
  private static final String domainName = "notary";
  private static final String roleName = "user";
  private static final String senderName = "sender";
  private static final String senderId = String.format("%s@%s", senderName, domainName);
  private static final String receiverName = "receiver";
  private static final String receiverId = String.format("%s@%s", receiverName, domainName);
  private static final String validatorName = "brvs";
  private static final String validatorId = String.format("%s@%s", validatorName, domainName);
  private static final String asset = "bux";
  private static final String assetId = String.format("%s#%s", asset, domainName);
  private static final int INITIALIZATION_TIME = 5000;
  private CacheProvider cacheProvider;
  private TransactionVerdictStorage transactionVerdictStorage;
  private AccountManager accountManager;
  private static final GenericContainer rmq = new GenericContainer<>("rabbitmq:3-management")
      .withExposedPorts(5672);
  private static final GenericContainer mongo = new GenericContainer<>("mongo:4.0.6")
      .withExposedPorts(27017);
  private static final WaitForTerminalStatus terminalStrategy = new WaitForTerminalStatus(
      Arrays.asList(
          TxStatus.COMMITTED,
          TxStatus.REJECTED
      ));

  private IrohaContainer iroha;
  private IrohaAPI irohaAPI;
  private String rmqHost;
  private Integer rmqPort;
  private String mongoHost;
  private Integer mongoPort;
  private ValidationService validationService;

  private static BlockOuterClass.Block getGenesisBlock() {
    return new GenesisBlockBuilder()
        // first transaction
        .addTransaction(
            Transaction.builder(validatorId)
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
                        RolePermission.can_get_all_acc_ast,
                        RolePermission.can_get_all_acc_detail,
                        RolePermission.can_grant_can_add_my_signatory,
                        RolePermission.can_grant_can_set_my_quorum,
                        RolePermission.can_set_detail
                    )
                )
                .createDomain(domainName, roleName)
                // brvs account
                .createAccount(validatorName, domainName, validatorKeypair.getPublic())
                // create receiver acc
                .createAccount(receiverName, domainName, receiverKeypair.getPublic())
                // create sender acc
                .createAccount(senderName, domainName, senderKeypair.getPublic())
                // account holder
                .createAccount(domainName, domainName, crypto.generateKeypair().getPublic())
                .setAccountDetail(domainName + "@" + domainName, receiverName + domainName,
                    domainName)
                .setAccountDetail(domainName + "@" + domainName, senderName + domainName,
                    domainName)
                .createAsset(asset, domainName, 0)
                // transactions in genesis block can be unsigned
                .build()
                .build()
        )
        .addTransaction(
            // add some assets to receiver acc
            Transaction.builder(receiverId)
                .addAssetQuantity(assetId, "10")
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

  private ValidationService getService(IrohaAPI irohaAPI) {
    final String accountsHolderAccount = String.format("%s@%s", domainName, domainName);
    final QueryAPI queryAPI = new QueryAPI(irohaAPI, validatorId, validatorKeypair);
    accountManager = new AccountManager(queryAPI,
        "uq",
        domainName,
        accountsHolderAccount,
        accountsHolderAccount,
        Collections.singletonList(validatorKeypair)
    );
    transactionVerdictStorage = new MongoTransactionVerdictStorage(mongoHost, mongoPort);
    final Map<String, Rule> ruleMap = new HashMap<>();
    ruleMap.put("sample", new SampleRule());
    ruleMap.put("volume", new TransferTxVolumeRule(assetId, new BigDecimal(150)));
    final BrvsIrohaChainListener brvsIrohaChainListener = new BrvsIrohaChainListener(
        new RMQConfig() {
          @NotNull
          @Override
          public String getHost() {
            return rmqHost;
          }

          @Override
          public int getPort() {
            return rmqPort;
          }

          @NotNull
          @Override
          public String getIrohaExchange() {
            return "iroha";
          }
        },
        queryAPI,
        validatorKeypair
    );
    final SimpleAggregationValidator validator = new SimpleAggregationValidator(ruleMap);
    return new ValidationServiceImpl(new ValidationServiceContext(
        validator,
        new BasicTransactionProvider(
            transactionVerdictStorage,
            cacheProvider,
            accountManager,
            accountManager,
            new MongoBlockStorage(mongoHost, mongoPort),
            brvsIrohaChainListener,
            domainName
        ),
        new TransactionSignerImpl(
            irohaAPI,
            Collections.singletonList(validatorKeypair),
            validatorId,
            validatorKeypair,
            transactionVerdictStorage
        ),
        accountManager,
        new BrvsData(Utils.toHex(receiverKeypair.getPublic().getEncoded()), "localhost"),
        new RuleMonitor(
            queryAPI,
            brvsIrohaChainListener,
            validatorId,
            validatorId,
            validatorId,
            validator
        )
    ));
  }

  @BeforeAll
  void setUp() throws InterruptedException {
    iroha = new IrohaContainer()
        .withPeerConfig(getPeerConfig())
        .withLogger(null);

    iroha.start();
    irohaAPI = iroha.getApi();

    cacheProvider = new CacheProvider();

    rmq.start();
    rmqHost = rmq.getContainerIpAddress();
    rmqPort = rmq.getMappedPort(5672);

    mongo.start();
    mongoHost = mongo.getContainerIpAddress();
    mongoPort = mongo.getMappedPort(27017);

    Thread.sleep(INITIALIZATION_TIME);

    irohaAPI.transactionSync(Transaction.builder(senderId)
        .grantPermission(validatorId, GrantablePermission.can_add_my_signatory)
        .grantPermission(validatorId, GrantablePermission.can_set_my_quorum)
        .sign(senderKeypair)
        .build()
    );
    irohaAPI.transactionSync(Transaction.builder(receiverId)
        .grantPermission(validatorId, GrantablePermission.can_add_my_signatory)
        .grantPermission(validatorId, GrantablePermission.can_set_my_quorum)
        .sign(receiverKeypair)
        .build()
    );
    irohaAPI.transactionSync(Transaction.builder(receiverId)
        .grantPermission(validatorId, GrantablePermission.can_add_my_signatory)
        .grantPermission(validatorId, GrantablePermission.can_set_my_quorum)
        .sign(receiverKeypair)
        .build()
    );

    // construct BRVS using some account for block streaming and validator keypair
    validationService = getService(irohaAPI);
    Thread.sleep(INITIALIZATION_TIME);
    // subscribe to new transactions
    validationService.verifyTransactions();
  }

  @AfterAll
  void tearDown() {
    irohaAPI.close();
    iroha.close();
    rmq.stop();
    mongo.stop();
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
  void createAccountTransactionOnTransferLimitValidatorTest() {
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
    cacheProvider.unlockPendingAccount(receiverId);
    irohaAPI.transactionSync(transaction);

    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe(status -> {
      if (status.getTxStatus().equals(TxStatus.ENOUGH_SIGNATURES_COLLECTED)) {
        // Check account is blocked
        assertNull(cacheProvider.getAccountBlockedBy(ValidationUtils.hexHash(transaction)));
      }
    });

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
  void validTransferAssetOnTransferLimitValidatorTest() {
    final String initialBalance = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccountAssets(receiverId)
        .buildSigned(receiverKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().get(0).getBalance();
    // send valid transfer asset transaction
    final String amount = "100";
    TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", amount)
        .setQuorum(2)
        .sign(senderKeypair).build();
    cacheProvider.unlockPendingAccount(senderId);
    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe(status -> {
      if (status.getTxStatus().equals(TxStatus.ENOUGH_SIGNATURES_COLLECTED)) {
        // Check account is blocked
        assertEquals(senderId,
            cacheProvider.getAccountBlockedBy(ValidationUtils.hexHash(transaction)));
      }
    });

    assertEquals(Verdict.VALIDATED, transactionVerdictStorage
        .getTransactionVerdict(ValidationUtils.hexHash(transaction)).getStatus());

    // query Iroha and check that transfer was committed
    AccountAsset accountAsset = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccountAssets(receiverId)
        .buildSigned(receiverKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().get(0);
    assertEquals(assetId, accountAsset.getAssetId());
    final BigDecimal bigDecimal = new BigDecimal(initialBalance);
    assertEquals(bigDecimal.add(new BigDecimal(amount)).toPlainString(), accountAsset.getBalance());
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
    final String initialBalance = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccountAssets(receiverId)
        .buildSigned(receiverKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().get(0).getBalance();

    // send invalid transfer asset transaction
    TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test invalid transfer", "200")
        .setQuorum(2)
        .sign(senderKeypair).build();
    cacheProvider.unlockPendingAccount(senderId);

    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe(status -> {
      if (status.getTxStatus().equals(TxStatus.ENOUGH_SIGNATURES_COLLECTED)) {
        // Check account is not blocked
        assertNull(cacheProvider.getAccountBlockedBy(ValidationUtils.hexHash(transaction)));
      }
    });

    assertEquals(Verdict.REJECTED, transactionVerdictStorage
        .getTransactionVerdict(ValidationUtils.hexHash(transaction)).getStatus());
    assertEquals(TxStatus.REJECTED,
        irohaAPI.txStatusSync(Utils.hash(transaction)).getTxStatus());

    // query Iroha and check that transfer was not committed
    AccountAsset accountAsset = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccountAssets(receiverId)
        .buildSigned(receiverKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().get(0);
    assertEquals(assetId, accountAsset.getAssetId());
    assertEquals(initialBalance, accountAsset.getBalance());
  }
}
