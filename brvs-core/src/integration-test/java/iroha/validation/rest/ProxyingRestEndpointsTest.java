/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest;

import static iroha.validation.utils.ValidationUtils.crypto;
import static java.nio.charset.StandardCharsets.UTF_8;
import static jp.co.soramitsu.iroha.java.Utils.createTxList;
import static org.mockito.Mockito.mock;

import com.google.gson.Gson;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;
import com.google.protobuf.util.JsonFormat.Printer;
import iroha.protocol.BlockOuterClass;
import iroha.protocol.Endpoint.TxList;
import iroha.protocol.Primitive.RolePermission;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.protocol.Queries.Query;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.rest.dto.BinaryTransaction;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer;
import jp.co.soramitsu.iroha.testcontainers.PeerConfig;
import jp.co.soramitsu.iroha.testcontainers.detail.GenesisBlockBuilder;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Assert;
import org.junit.Test;
import org.testcontainers.shaded.org.apache.commons.codec.binary.Hex;
import org.testcontainers.shaded.org.apache.commons.io.IOUtils;

public class ProxyingRestEndpointsTest extends JerseyTest {

  private static final KeyPair peerKeypair = crypto.generateKeypair();
  private static final KeyPair senderKeypair = crypto.generateKeypair();
  private static final KeyPair senderSecondKeypair = crypto.generateKeypair();
  private static final KeyPair receiverKeypair = crypto.generateKeypair();
  private static final String userDomainName = "user";
  private static final String roleName = "user";
  private static final String senderName = "sender";
  private static final String senderId = String.format("%s@%s", senderName, userDomainName);
  private static final String receiverName = "receiver";
  private static final String receiverId = String.format("%s@%s", receiverName, userDomainName);
  private static final String asset = "bux";
  private static final String assetId = String.format("%s#%s", asset, userDomainName);
  private final static Printer printer = JsonFormat.printer()
      .omittingInsignificantWhitespace()
      .preservingProtoFieldNames();
  private final static Parser parser = JsonFormat.parser().ignoringUnknownFields();
  private static IrohaContainer iroha;
  private static IrohaAPI irohaAPI;
  private static final Gson gson = new Gson();

  private static BlockOuterClass.Block getGenesisBlock() {
    return new GenesisBlockBuilder()
        // first transaction
        .addTransaction(
            Transaction.builder(null)
                // by default peer is listening on port 10001
                .addPeer("0.0.0.0:10001", peerKeypair.getPublic())
                // create default role
                .createRole(roleName,
                    Arrays.asList(
                        RolePermission.can_add_signatory,
                        RolePermission.can_transfer,
                        RolePermission.can_receive,
                        RolePermission.can_add_asset_qty,
                        RolePermission.can_get_all_accounts
                    )
                )
                .createDomain(userDomainName, roleName)
                // create receiver acc
                .createAccount(receiverName, userDomainName, receiverKeypair.getPublic())
                // create sender acc
                .createAccount(senderName, userDomainName, senderKeypair.getPublic())
                .addSignatory(senderId, senderSecondKeypair.getPublic())
                .createAsset(asset, userDomainName, 0)
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

  /**
   * Encode protobuf transaction
   *
   * @param bytes - raw bytes of protobuf transaction
   * @return BinaryTransaction object
   */
  private static BinaryTransaction getBinaryTransaction(byte[] bytes) {
    return new BinaryTransaction(Hex.encodeHexString(bytes));
  }

  static {
    iroha = new IrohaContainer()
        .withPeerConfig(getPeerConfig())
        .withLogger(null);

    iroha.withIrohaAlias("d3-iroha").start();
    irohaAPI = iroha.getApi();
  }

  @Override
  protected Application configure() {
    final ResourceConfig resourceConfig = new ResourceConfig(BusinessController.class)
        .packages("iroha.validation.rest");
    resourceConfig.register(new AbstractBinder() {
      @Override
      protected void configure() {
        bind(mock(TransactionVerdictStorage.class)).to(TransactionVerdictStorage.class);
        bind(mock(RegistrationProvider.class)).to(RegistrationProvider.class);
        bind(irohaAPI).to(IrohaAPI.class);
        bind(senderSecondKeypair).to(KeyPair.class);
      }
    });
    return resourceConfig;
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Transaction} with a valid creator's signature is passed to the '/transaction'
   * @then BRVS proxies the transaction and returns successful status code 200 with status stream
   */
  @Test
  public void sendTransaction() throws IOException {
    final String amount = "1";
    final TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", amount)
        .sign(senderKeypair)
        .build();

    Response response = target("/transaction/send").request().post(
        Entity.entity(
            printer.print(transaction),
            MediaType.APPLICATION_JSON_TYPE
        )
    );

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    Assert.assertTrue(IOUtils.toString((InputStream) response.getEntity(), UTF_8)
        .contains("COMMITTED")
    );
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Transaction} without a valid creator's signature is passed to the
   * '/transaction/sign'
   * @then BRVS proxies the transaction, signs it and returns successful status code 200 with status
   * stream
   */
  @Test
  public void sendUnsignedTransaction() throws IOException {
    final String amount = "1";
    final TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", amount)
        .build()
        .build();

    Response response = target("/transaction/send/sign").request().post(
        Entity.entity(
            printer.print(transaction),
            MediaType.APPLICATION_JSON_TYPE
        )
    );

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    Assert.assertTrue(IOUtils.toString((InputStream) response.getEntity(), UTF_8)
        .contains("COMMITTED")
    );
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Transaction} with a valid creator's signature is passed to the
   * '/transaction/sendBinary' as byte array
   * @then BRVS proxies the transaction and returns successful status code 200 with status stream
   */
  @Test
  public void sendBinaryTransaction() throws IOException {
    final String amount = "1";
    final TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", amount)
        .sign(senderKeypair)
        .build();
    BinaryTransaction bt = getBinaryTransaction(transaction.toByteArray());

    Response response = target("/transaction/sendBinary/sign").request().post(
        Entity.entity(
            gson.toJson(bt),
            MediaType.APPLICATION_JSON_TYPE
        )
    );

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    Assert.assertTrue(IOUtils.toString((InputStream) response.getEntity(), UTF_8)
        .contains("COMMITTED")
    );
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Transaction} without a valid creator's signature is passed to the
   * '/transaction/sendBinary/sign' as byte array
   * @then BRVS proxies the transaction, signs it and returns successful status code 200 with status
   * * stream
   */
  @Test
  public void sendBinaryUnsignedTransaction() throws IOException {
    final String amount = "1";
    final TransactionOuterClass.Transaction transaction = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer", amount)
        .build()
        .build();
    BinaryTransaction bt = getBinaryTransaction(transaction.toByteArray());

    Response response = target("/transaction/sendBinary/sign").request().post(
        Entity.entity(
            gson.toJson(bt),
            MediaType.APPLICATION_JSON_TYPE
        )
    );

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    Assert.assertTrue(IOUtils.toString((InputStream) response.getEntity(), UTF_8)
        .contains("COMMITTED")
    );
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Query} with a valid creator's signature is passed to the '/query'
   * @then BRVS proxies the query, signs it and returns successful status code 200 with a
   * corresponding query response
   */
  @Test
  public void sendQuery() throws IOException {
    final Query query = jp.co.soramitsu.iroha.java.Query.builder(senderId, 1).getAccount(senderId)
        .buildSigned(senderKeypair);

    Response response = target("/query/send").request()
        .post(Entity.entity(printer.print(query), MediaType.APPLICATION_JSON_TYPE));

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());

    final QueryResponse.Builder builder = QueryResponse.newBuilder();
    parser.merge(IOUtils.toString((InputStream) response.getEntity(), UTF_8), builder);

    Assert.assertNotNull(builder.getAccountResponse());
    Assert.assertEquals(senderId, builder.getAccountResponse().getAccount().getAccountId());
    Assert.assertEquals(1, builder.getAccountResponse().getAccount().getQuorum());
    Assert.assertEquals(roleName, builder.getAccountResponse().getAccountRoles(0));
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Query} without a signature is passed to the '/query/sign'
   * @then BRVS proxies the query, signs it and returns successful status code 200 with a
   * corresponding query response
   */
  @Test
  public void sendUnsignedQuery() throws IOException {
    final Query query = jp.co.soramitsu.iroha.java.Query.builder(senderId, 1).getAccount(senderId)
        .buildUnsigned();

    Response response = target("/query/send/sign").request()
        .post(Entity.entity(printer.print(query), MediaType.APPLICATION_JSON_TYPE));

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());

    final QueryResponse.Builder builder = QueryResponse.newBuilder();
    parser.merge(IOUtils.toString((InputStream) response.getEntity(), UTF_8), builder);

    Assert.assertNotNull(builder.getAccountResponse());
    Assert.assertEquals(senderId, builder.getAccountResponse().getAccount().getAccountId());
    Assert.assertEquals(1, builder.getAccountResponse().getAccount().getQuorum());
    Assert.assertEquals(roleName, builder.getAccountResponse().getAccountRoles(0));
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Transaction} batch with a valid creator's signature for each contained transaction
   * is passed to the '/batch'
   * @then BRVS proxies the transaction and returns successful status code 200 with first
   * transaction status stream
   */
  @Test
  public void sendBatch() throws IOException {
    final String amount = "1";
    final TransactionOuterClass.Transaction transaction1 = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer1", amount)
        .build()
        .build();
    final TransactionOuterClass.Transaction transaction2 = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer2", amount)
        .build()
        .build();
    final Iterable<TransactionOuterClass.Transaction> txAtomicBatch = Utils
        .createTxAtomicBatch(Arrays.asList(transaction1, transaction2), senderKeypair);

    Response response = target("/batch/send").request()
        .post(
            Entity.entity(
                printer.print(createTxList(txAtomicBatch)),
                MediaType.APPLICATION_JSON_TYPE
            )
        );

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    Assert.assertTrue(IOUtils.toString((InputStream) response.getEntity(), UTF_8)
        .contains("COMMITTED")
    );
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Transaction} batch without a signature in each contained transaction is passed to
   * the '/batch/sign'
   * @then BRVS proxies the transaction, signs it and returns successful status code 200 with first
   * transactions status stream
   */
  @Test
  public void sendUnsignedBatch() throws IOException {
    final String amount = "1";
    final Transaction transaction1 = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer1", amount)
        .build();
    final Transaction transaction2 = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer2", amount)
        .build();
    final List<Transaction> txAtomicBatch = (List<Transaction>) Utils
        .createTxUnsignedAtomicBatch(Arrays.asList(transaction1, transaction2));

    Response response = target("/batch/send/sign").request()
        .post(
            Entity.entity(
                printer.print(
                    createTxList(
                        Arrays.asList(
                            txAtomicBatch.get(0).build(),
                            txAtomicBatch.get(1).build()
                        )
                    )
                ),
                MediaType.APPLICATION_JSON_TYPE
            )
        );

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    Assert.assertTrue(IOUtils.toString((InputStream) response.getEntity(), UTF_8)
        .contains("COMMITTED")
    );
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Transaction} with a valid creator's signature is passed to the '/batch/sendBinary'
   * as byte array
   * @then BRVS proxies the transaction and returns successful status code 200 with status stream
   */
  @Test
  public void sendBinaryBatch() throws IOException {
    final String amount = "1";
    final TransactionOuterClass.Transaction transaction1 = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer1", amount)
        .build()
        .build();
    final TransactionOuterClass.Transaction transaction2 = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer2", amount)
        .build()
        .build();

    final Iterable<TransactionOuterClass.Transaction> txAtomicBatch = Utils
        .createTxAtomicBatch(Arrays.asList(transaction1, transaction2), senderKeypair);

    TxList.Builder txListBuilder = TxList.newBuilder();
    txAtomicBatch.forEach(txListBuilder::addTransactions);
    TxList txList = txListBuilder.build();
    BinaryTransaction bt = getBinaryTransaction(txList.toByteArray());

    Response response = target("/batch/sendBinary/sign").request().post(
        Entity.entity(
            gson.toJson(bt),
            MediaType.APPLICATION_JSON_TYPE
        )
    );

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    Assert.assertTrue(IOUtils.toString((InputStream) response.getEntity(), UTF_8)
        .contains("COMMITTED")
    );
  }

  /**
   * @given {@link BusinessController} instance with a creator's signature inside
   * @when {@link Transaction} without a valid creator's signature is passed to the
   * '/batch/sendBinary/sign' as byte array
   * @then BRVS proxies the transaction, signs it and returns successful status code 200 with status
   * stream
   */
  @Test
  public void sendBinaryUnsignedBatch() throws IOException {
    final String amount = "1";
    final Transaction transaction1 = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer1", amount)
        .build();
    final Transaction transaction2 = Transaction.builder(senderId)
        .transferAsset(senderId, receiverId, assetId, "test valid transfer2", amount)
        .build();
    TxList txList = TxList.newBuilder()
        .addTransactions(transaction1.build())
        .addTransactions(transaction2.build())
        .build();
    BinaryTransaction bt = getBinaryTransaction(txList.toByteArray());

    Response response = target("/batch/sendBinary/sign").request().post(
        Entity.entity(
            gson.toJson(bt),
            MediaType.APPLICATION_JSON_TYPE
        )
    );

    Assert.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    Assert.assertTrue(IOUtils.toString((InputStream) response.getEntity(), UTF_8)
        .contains("COMMITTED")
    );
  }
}
