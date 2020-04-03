/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.behavior;

import static iroha.validation.transactions.plugin.impl.SoraDistributionPluggableLogic.DISTRIBUTION_FINISHED_KEY;
import static iroha.validation.transactions.plugin.impl.SoraDistributionPluggableLogic.DISTRIBUTION_PROPORTIONS_KEY;
import static iroha.validation.utils.ValidationUtils.advancedQueryAccountDetails;
import static iroha.validation.utils.ValidationUtils.crypto;
import static iroha.validation.utils.ValidationUtils.gson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d3.chainadapter.client.RMQConfig;
import com.google.common.io.Files;
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
import iroha.validation.transactions.plugin.impl.SoraDistributionPluggableLogic;
import iroha.validation.transactions.plugin.impl.SoraDistributionPluggableLogic.SoraDistributionFinished;
import iroha.validation.transactions.plugin.impl.SoraDistributionPluggableLogic.SoraDistributionProportions;
import iroha.validation.transactions.provider.impl.AccountManager;
import iroha.validation.transactions.provider.impl.BasicTransactionProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.transactions.signatory.impl.TransactionSignerImpl;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.transactions.storage.impl.mongo.MongoTransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.validators.impl.SimpleAggregationValidator;
import iroha.validation.verdict.Verdict;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.QueryBuilder;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.java.subscription.WaitForTerminalStatus;
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer;
import jp.co.soramitsu.iroha.testcontainers.PeerConfig;
import jp.co.soramitsu.iroha.testcontainers.detail.GenesisBlockBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.Alphanumeric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;

@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(Alphanumeric.class)
public class IrohaIntegrationTest {

  private static final KeyPair peerKeypair = crypto.generateKeypair();
  private static final KeyPair senderKeypair = crypto.generateKeypair();
  private static final KeyPair receiverKeypair = crypto.generateKeypair();
  private static final KeyPair validatorKeypair = crypto.generateKeypair();
  private static final KeyPair projectOwnerKeypair = crypto.generateKeypair();
  private static final String serviceDomainName = "sora";
  private static final String userDomainName = "user";
  private static final String roleName = "user";
  private static final String senderName = "sender";
  private static final String projectOwner = "owner";
  private static final String projectParticipantOne = "participantone";
  private static final String projectParticipantTwo = "participanttwo";
  private static final String projectParticipantThree = "participantthree";
  private static final String projectOwnerId = String.format("%s@%s", projectOwner, userDomainName);
  private static final String projectParticipantOneId = String
      .format("%s@%s", projectParticipantOne, userDomainName);
  private static final String projectParticipantTwoId = String
      .format("%s@%s", projectParticipantTwo, userDomainName);
  private static final String projectParticipantThreeId = String
      .format("%s@%s", projectParticipantThree, userDomainName);
  private static final String senderId = String.format("%s@%s", senderName, userDomainName);
  private static final String receiverName = "receiver";
  private static final String receiverId = String.format("%s@%s", receiverName, userDomainName);
  private static final String validatorName = "brvs";
  private static final String validatorConfigName = "brvssettings";
  private static final String validatorId = String
      .format("%s@%s", validatorName, serviceDomainName);
  private static final String validatorConfigId = String.format("%s@%s", validatorConfigName,
      serviceDomainName);
  private static final String asset = "xor";
  private static final String assetId = String.format("%s#%s", asset, serviceDomainName);
  private static final int INITIALIZATION_TIME = 5000;
  private TransactionVerdictStorage transactionVerdictStorage;
  private AccountManager accountManager;
  private static final GenericContainer rmq = new GenericContainer<>("rabbitmq:3-management")
      .withExposedPorts(5672)
      .withCreateContainerCmdModifier(modifier -> modifier.withName("d3-rmq"));
  private static final GenericContainer mongo = new GenericContainer<>("mongo:4.0.6")
      .withExposedPorts(27017);
  private static final GenericContainer chainAdapter = new GenericContainer<>(
      "nexus.iroha.tech:19002/d3-deploy/chain-adapter:develop");
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
  private ValidationServiceImpl validationService;

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
                        RolePermission.can_set_detail,
                        RolePermission.can_get_blocks
                    )
                )
                .createDomain(serviceDomainName, roleName)
                .createDomain(userDomainName, roleName)
                // brvs accounts
                .createAccount(validatorName, serviceDomainName, validatorKeypair.getPublic())
                .createAccount(validatorConfigName, serviceDomainName, validatorKeypair.getPublic())
                // create receiver acc
                .createAccount(receiverName, userDomainName, receiverKeypair.getPublic())
                // create sender acc
                .createAccount(senderName, userDomainName, senderKeypair.getPublic())
                // account holder
                .createAccount(serviceDomainName, serviceDomainName,
                    crypto.generateKeypair().getPublic())
                .setAccountDetail(String.format("%s@%s", serviceDomainName, serviceDomainName),
                    senderName + userDomainName, userDomainName)
                .setAccountDetail(String.format("%s@%s", serviceDomainName, serviceDomainName),
                    receiverName + userDomainName, userDomainName)
                .createAccount("rmq", serviceDomainName, Utils.parseHexPublicKey(
                    "7a4af859a775dd7c7b4024c97c8118f0280455b8135f6f41422101f0397e0fa5"))
                .createAsset(asset, serviceDomainName, 18)
                // create project owner acc
                .createAccount(projectOwner, userDomainName, projectOwnerKeypair.getPublic())
                // create project participants accs
                .createAccount(
                    projectParticipantOne,
                    userDomainName,
                    projectOwnerKeypair.getPublic()
                )
                .createAccount(
                    projectParticipantTwo,
                    userDomainName,
                    projectOwnerKeypair.getPublic()
                )
                .createAccount(
                    projectParticipantThree,
                    userDomainName,
                    projectOwnerKeypair.getPublic()
                )
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
        .addTransaction(
            Transaction.builder(projectParticipantOneId)
                .addAssetQuantity(assetId, "1")
                .sign(projectOwnerKeypair)
                .build()
        )
        .addTransaction(
            Transaction.builder(projectParticipantTwoId)
                .addAssetQuantity(assetId, "1")
                .sign(projectOwnerKeypair)
                .build()
        )
        .addTransaction(
            Transaction.builder(projectParticipantThreeId)
                .addAssetQuantity(assetId, "1")
                .sign(projectOwnerKeypair)
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

  private ValidationServiceImpl getService(IrohaAPI irohaAPI) {
    final String accountsHolderAccount = String.format("%s@%s", serviceDomainName,
        serviceDomainName);
    final QueryAPI queryAPI = new QueryAPI(irohaAPI, validatorId, validatorKeypair);
    accountManager = new AccountManager(queryAPI,
        "uq",
        userDomainName,
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
          @Override
          public String getHost() {
            return rmqHost;
          }

          @Override
          public int getPort() {
            return rmqPort;
          }

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
            accountManager,
            accountManager,
            new SoraDistributionPluggableLogic(
                queryAPI,
                projectOwnerId,
                projectOwnerId
            ),
            brvsIrohaChainListener,
            userDomainName
        ),
        new TransactionSignerImpl(
            irohaAPI,
            Collections.singletonList(validatorKeypair),
            validatorId,
            validatorKeypair,
            transactionVerdictStorage,
            accountManager
        ),
        accountManager,
        new BrvsData(Utils.toHex(receiverKeypair.getPublic().getEncoded()), "localhost"),
        new RuleMonitor(
            queryAPI,
            brvsIrohaChainListener,
            validatorId,
            validatorConfigId,
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

    iroha.withIrohaAlias("d3-iroha").start();
    irohaAPI = iroha.getApi();

    rmq.withNetwork(iroha.getNetwork()).start();
    rmqHost = rmq.getContainerIpAddress();
    rmqPort = rmq.getMappedPort(5672);

    mongo.withNetwork(iroha.getNetwork()).start();
    mongoHost = mongo.getContainerIpAddress();
    mongoPort = mongo.getMappedPort(27017);

    Thread.sleep(INITIALIZATION_TIME);

    chainAdapter
        .withEnv("CHAIN-ADAPTER_DROPLASTEREADBLOCK", "true")
        .withEnv("CHAIN-ADAPTER_IROHACREDENTIAL_ACCOUNTID", validatorId)
        .withEnv(
            "CHAIN-ADAPTER_IROHACREDENTIAL_PUBKEY",
            Utils.toHex(validatorKeypair.getPublic().getEncoded())
        )
        .withEnv(
            "CHAIN-ADAPTER_IROHACREDENTIAL_PRIVKEY",
            Utils.toHex(validatorKeypair.getPrivate().getEncoded())
        )
        .withNetwork(iroha.getNetwork())
        .start();

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

    // construct BRVS using some account for block streaming and validator keypair
    validationService = getService(irohaAPI);
    Thread.sleep(INITIALIZATION_TIME);
    // subscribe to new transactions
    validationService.verifyTransactions();
  }

  @AfterAll
  void tearDown() throws IOException {
    validationService.close();
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
            serviceDomainName,
            crypto.generateKeypair().getPublic()
        )
        .setQuorum(2)
        .sign(receiverKeypair)
        .build();

    final String txHash = ValidationUtils.hexHash(transaction);
    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe();

    assertEquals(Verdict.VALIDATED,
        transactionVerdictStorage.getTransactionVerdict(txHash).getStatus());

    // query Iroha and check
    String newAccountId = String.format("%s@%s", newAccountName, serviceDomainName);
    Account accountResponse = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccount(newAccountId)
        .buildSigned(receiverKeypair))
        .getAccountResponse()
        .getAccount();
    assertEquals(newAccountId, accountResponse.getAccountId());
    assertEquals(serviceDomainName, accountResponse.getDomainId());
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

    final String txHash = ValidationUtils.hexHash(transaction);
    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe();

    assertEquals(Verdict.VALIDATED,
        transactionVerdictStorage.getTransactionVerdict(txHash).getStatus());

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
  void invalidTransferAssetOnTransferLimitValidatorTest() {
    final String initialBalance = irohaAPI.query(new QueryBuilder(receiverId, Instant.now(), 1)
        .getAccountAssets(receiverId)
        .buildSigned(receiverKeypair))
        .getAccountAssetsResponse()
        .getAccountAssetsList().get(0).getBalance();

    // send invalid transfer asset transaction
    final String amount = "200";
    TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test invalid transfer", amount)
        .setQuorum(2)
        .sign(senderKeypair).build();

    final String txHash = ValidationUtils.hexHash(transaction);
    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe();

    assertEquals(Verdict.REJECTED,
        transactionVerdictStorage.getTransactionVerdict(txHash).getStatus());
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

  /**
   * @given {@link ValidationService} instance with {@link TransferTxVolumeRule} named "volume"
   * @when {@link Transaction} with {@link iroha.protocol.Commands.Command SetAccountDetails}
   * command of setting true to "volume" rule appears
   * @then {@link TransferTxVolumeRule} is NOT replaced by new rule
   */
  @Test
  void x_groovyRuleAlreadyExistsTest() throws IOException, InterruptedException {
    final String ruleName = "volume";
    final TxStatus volumeRepositoryStatus = irohaAPI.transaction(Transaction.builder(validatorId)
            .setAccountDetail(validatorId, ruleName, Utils.irohaEscape(
                Files
                    .toString(
                        new File("src/integration-test/resources/fake_rule.groovy"),
                        Charset.defaultCharset()
                    )
            ))
            .sign(validatorKeypair)
            .build(),
        terminalStrategy
    ).blockingLast().getTxStatus();
    // New volume rule is uploaded
    assertEquals(TxStatus.COMMITTED, volumeRepositoryStatus);

    final TxStatus volumeRuleStatus = irohaAPI.transaction(Transaction.builder(validatorId)
            .setAccountDetail(validatorConfigId, ruleName, "true")
            .sign(validatorKeypair)
            .build(),
        terminalStrategy
    ).blockingLast().getTxStatus();
    // Volume rule is enabled
    assertEquals(TxStatus.COMMITTED, volumeRuleStatus);

    // send valid transfer asset transaction
    final String amount = "100";
    TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", amount)
        .setQuorum(2)
        .sign(senderKeypair).build();
    final String txHash = ValidationUtils.hexHash(transaction);
    Thread.sleep(2000);
    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe();

    assertEquals(Verdict.VALIDATED,
        transactionVerdictStorage.getTransactionVerdict(txHash).getStatus());
  }

  /**
   * @given {@link ValidationService} instance with rules attached
   * @when {@link Transaction} with {@link iroha.protocol.Commands.Command SetAccountDetails}
   * command of setting true to new rule appears
   * @then {@link ValidationService} added the rule and transaction failed
   */
  @Test
  void y_groovyNewRuleTest() throws IOException, InterruptedException {
    final String ruleName = "new";
    final TxStatus volumeRepositoryStatus = irohaAPI.transaction(Transaction.builder(validatorId)
            .setAccountDetail(validatorId, ruleName, Utils.irohaEscape(
                Files
                    .toString(
                        new File("src/integration-test/resources/fake_rule.groovy"),
                        Charset.defaultCharset()
                    )
            ))
            .sign(validatorKeypair)
            .build(),
        terminalStrategy
    ).blockingLast().getTxStatus();
    // New rule is uploaded
    assertEquals(TxStatus.COMMITTED, volumeRepositoryStatus);

    final TxStatus volumeRuleStatus = irohaAPI.transaction(Transaction.builder(validatorId)
            .setAccountDetail(validatorConfigId, ruleName, "true")
            .sign(validatorKeypair)
            .build(),
        terminalStrategy
    ).blockingLast().getTxStatus();
    // Volume rule is enabled
    assertEquals(TxStatus.COMMITTED, volumeRuleStatus);

    // send valid transfer asset transaction
    final String amount = "100";
    TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", amount)
        .setQuorum(2)
        .sign(senderKeypair).build();
    final String txHash = ValidationUtils.hexHash(transaction);
    Thread.sleep(2000);
    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe();

    assertEquals(Verdict.REJECTED,
        transactionVerdictStorage.getTransactionVerdict(txHash).getStatus());

    // Disable bad rule
    irohaAPI.transaction(Transaction.builder(validatorId)
        .setAccountDetail(validatorId, ruleName, "false")
        .sign(validatorKeypair)
        .build());
  }

  /**
   * @given {@link ValidationService} instance with rules attached
   * @when {@link Transaction} with {@link iroha.protocol.Commands.Command SetAccountDetails}
   * command of setting false and true to existing rule appears
   * @then {@link ValidationService} added overwrote existing rule and transaction failed
   */
  @Test
  void z_groovyNewRuleOverwriteTest() throws IOException, InterruptedException {
    final String ruleName = "volume";
    final TxStatus volumeRepositoryStatus = irohaAPI.transaction(Transaction.builder(validatorId)
            .setAccountDetail(validatorId, ruleName, Utils.irohaEscape(
                Files
                    .toString(
                        new File("src/integration-test/resources/fake_rule.groovy"),
                        Charset.defaultCharset()
                    )
            ))
            .sign(validatorKeypair)
            .build(),
        terminalStrategy
    ).blockingLast().getTxStatus();
    // New volume rule is uploaded
    assertEquals(TxStatus.COMMITTED, volumeRepositoryStatus);

    final TxStatus volumeRuleStatus = irohaAPI.transaction(Transaction.builder(validatorId)
            .setAccountDetail(validatorConfigId, ruleName, "false")
            .setAccountDetail(validatorConfigId, ruleName, "true")
            .sign(validatorKeypair)
            .build(),
        terminalStrategy
    ).blockingLast().getTxStatus();
    // Volume rule is reenabled
    assertEquals(TxStatus.COMMITTED, volumeRuleStatus);

    // send valid transfer asset transaction
    final String amount = "100";
    TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", amount)
        .setQuorum(2)
        .sign(senderKeypair).build();
    final String txHash = ValidationUtils.hexHash(transaction);
    Thread.sleep(2000);
    irohaAPI.transaction(transaction, terminalStrategy).blockingSubscribe();

    assertEquals(Verdict.REJECTED,
        transactionVerdictStorage.getTransactionVerdict(txHash).getStatus());

    // Disable bad rule
    irohaAPI.transaction(Transaction.builder(validatorId)
        .setAccountDetail(validatorId, ruleName, "false")
        .sign(validatorKeypair)
        .build());
  }

  /**
   * @given {@link ValidationService} instance with {@link SoraDistributionPluggableLogic} attached
   * and relevant JSON with Sora distribution proportions provided
   * @when {@link Transaction} with {@link iroha.protocol.Commands.Command TransferAsset} command
   * going from a project owner appears
   * @then {@link ValidationService} performs needed transfer and settings
   */
  @Test
  void exactDistributionPortions() throws InterruptedException {
    // projectOwner is a json setter
    final Map<String, BigDecimal> proportionsMap = new HashMap<>();
    proportionsMap.put(projectParticipantOneId, new BigDecimal("0.5"));
    proportionsMap.put(projectParticipantTwoId, new BigDecimal("0.3"));
    proportionsMap.put(projectParticipantThreeId, new BigDecimal("0.2"));
    final BigDecimal totalSupply = new BigDecimal("1000");
    final SoraDistributionProportions proportions = new SoraDistributionProportions(
        proportionsMap,
        totalSupply
    );
    final QueryAPI queryAPI = new QueryAPI(irohaAPI, validatorId, validatorKeypair);

    BigDecimal oneBalance = new BigDecimal(queryAPI.getAccountAssets(projectParticipantOneId)
        .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
        .findAny().get().getBalance()
    );
    BigDecimal twoBalance = new BigDecimal(queryAPI.getAccountAssets(projectParticipantTwoId)
        .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
        .findAny().get().getBalance()
    );
    BigDecimal threeBalance = new BigDecimal(queryAPI.getAccountAssets(projectParticipantThreeId)
        .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
        .findAny().get().getBalance()
    );

    irohaAPI.transaction(
        Transaction.builder(validatorId)
            .addAssetQuantity(assetId, totalSupply)
            .sign(validatorKeypair)
            .build()
    ).blockingLast();
    irohaAPI.transaction(
        Transaction.builder(projectOwnerId)
            .addAssetQuantity(assetId, new BigDecimal("3000"))
            .setAccountDetail(
                projectOwnerId,
                DISTRIBUTION_PROPORTIONS_KEY,
                Utils.irohaEscape(gson.toJson(proportions))
            )
            .sign(projectOwnerKeypair)
            .build()
    ).blockingLast();

    final BigDecimal firstAmount = new BigDecimal("600");

    irohaAPI.transaction(
        Transaction.builder(projectOwnerId)
            .transferAsset(projectOwnerId, receiverId, assetId, "perevod nomer 1", firstAmount)
            .sign(projectOwnerKeypair)
            .build()
    ).blockingLast();

    Thread.sleep(5000);

    assertEquals(0, oneBalance.add(new BigDecimal("300"))
        .compareTo(new BigDecimal(queryAPI.getAccountAssets(projectParticipantOneId)
            .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
            .findAny().get().getBalance()))
    );
    assertEquals(0, twoBalance.add(new BigDecimal("180"))
        .compareTo(new BigDecimal(queryAPI.getAccountAssets(projectParticipantTwoId)
            .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
            .findAny().get().getBalance()))
    );
    assertEquals(0, threeBalance.add(new BigDecimal("120"))
        .compareTo(new BigDecimal(queryAPI.getAccountAssets(projectParticipantThreeId)
            .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
            .findAny().get().getBalance()))
    );
    final SoraDistributionFinished finished = advancedQueryAccountDetails(
        queryAPI,
        projectOwnerId,
        validatorId,
        DISTRIBUTION_FINISHED_KEY,
        SoraDistributionFinished.class
    );

    assertNotNull(finished);
    assertFalse(finished.getFinished());

    final BigDecimal secondAmount = firstAmount;

    irohaAPI.transaction(
        Transaction.builder(projectOwnerId)
            .transferAsset(projectOwnerId, receiverId, assetId, "perevod nomer 2", secondAmount)
            .sign(projectOwnerKeypair)
            .build()
    ).blockingLast();

    Thread.sleep(5000);

    assertEquals(0, oneBalance.add(new BigDecimal("300")).add(new BigDecimal("200"))
        .compareTo(new BigDecimal(queryAPI.getAccountAssets(projectParticipantOneId)
            .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
            .findAny().get().getBalance()))
    );
    assertEquals(0, twoBalance.add(new BigDecimal("180")).add(new BigDecimal("120"))
        .compareTo(new BigDecimal(queryAPI.getAccountAssets(projectParticipantTwoId)
            .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
            .findAny().get().getBalance()))
    );
    assertEquals(0, threeBalance.add(new BigDecimal("120")).add(new BigDecimal("80"))
        .compareTo(new BigDecimal(queryAPI.getAccountAssets(projectParticipantThreeId)
            .getAccountAssetsList().stream().filter(result -> result.getAssetId().equals(assetId))
            .findAny().get().getBalance()))
    );
    final SoraDistributionFinished finishedNow = advancedQueryAccountDetails(
        queryAPI,
        projectOwnerId,
        validatorId,
        DISTRIBUTION_FINISHED_KEY,
        SoraDistributionFinished.class
    );

    assertNotNull(finishedNow);
    assertTrue(finishedNow.getFinished());
  }
}
