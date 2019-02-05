package iroha.validation.behavior;

import iroha.protocol.BlockOuterClass;
import iroha.protocol.Primitive.RolePermission;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.transactions.storage.TransactionProvider;
import iroha.validation.transactions.storage.impl.BasicTransactionProvider;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer;
import jp.co.soramitsu.iroha.testcontainers.PeerConfig;
import jp.co.soramitsu.iroha.testcontainers.detail.GenesisBlockBuilder;
import org.testcontainers.shaded.org.apache.commons.lang.RandomStringUtils;

public class IrohaIntegrationTest {

  private static final Ed25519Sha3 crypto = new Ed25519Sha3();
  private static final KeyPair peerKeypair = crypto.generateKeypair();
  private static final KeyPair userKeypair = crypto.generateKeypair();
  private static final String domainName = "domain";
  private static final String roleName = "user";
  private static final String userName = "user";
  private static final String userId = String.format("%s@%s", userName, domainName);

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
                        RolePermission.can_get_blocks
                    )
                )
                .createDomain(domainName, roleName)
                // create user
                .createAccount(userName, domainName, userKeypair.getPublic())
                .addSignatory(userId, crypto.generateKeypair().getPublic())
                .setAccountQuorum(userId, 1)
                // transactions in genesis block can be unsigned
                .build()
                .build()
        ).build();
  }

  public static PeerConfig getPeerConfig() {
    PeerConfig config = PeerConfig.builder()
        .genesisBlock(getGenesisBlock())
        .build();

    // don't forget to add peer keypair to config
    config.withPeerKeyPair(peerKeypair);

    return config;
  }

  public static void main(String[] args) {
    IrohaContainer iroha = new IrohaContainer()
        .withPeerConfig(getPeerConfig());

    iroha.start();

    IrohaAPI irohaAPI = new IrohaAPI(iroha.getToriiAddress());

    Executors.newScheduledThreadPool(1)
        .scheduleAtFixedRate(() -> {
          sendTx(irohaAPI);
        }, 1, 4, TimeUnit.SECONDS);

    TransactionProvider provider = new BasicTransactionProvider(irohaAPI, userId, userKeypair);

    // Just print arriving pending transactions
    provider.getPendingTransactionsStreaming().subscribe(System.out::println);
  }

  private static void sendTx(IrohaAPI api) {
    TransactionOuterClass.Transaction transaction = Transaction.builder(userId)
        .createAccount(
            RandomStringUtils.random(9, "abcdefghijklmnoprstvwxyz"),
            domainName,
            crypto.generateKeypair().getPublic()
        )
        .setQuorum(2)
        .sign(userKeypair).build();
    api.transactionSync(transaction);
  }
}
