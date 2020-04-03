/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.utils;

import static jp.co.soramitsu.crypto.ed25519.spec.EdDSANamedCurveTable.ED_25519;
import static jp.co.soramitsu.iroha.java.Utils.IROHA_FRIENDLY_NEW_LINE;
import static jp.co.soramitsu.iroha.java.Utils.IROHA_FRIENDLY_QUOTE;

import com.d3.chainadapter.client.RMQConfig;
import com.d3.commons.config.ConfigsKt;
import com.d3.commons.util.GsonInstance;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.bind.DatatypeConverter;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.crypto.ed25519.EdDSAPrivateKey;
import jp.co.soramitsu.crypto.ed25519.EdDSAPublicKey;
import jp.co.soramitsu.crypto.ed25519.spec.EdDSANamedCurveTable;
import jp.co.soramitsu.crypto.ed25519.spec.EdDSAParameterSpec;
import jp.co.soramitsu.crypto.ed25519.spec.EdDSAPublicKeySpec;
import jp.co.soramitsu.iroha.java.FieldValidator;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.java.subscription.SubscriptionStrategy;
import jp.co.soramitsu.iroha.java.subscription.WaitForTerminalStatus;

public interface ValidationUtils {

  EdDSAParameterSpec EdDSASpec = EdDSANamedCurveTable.getByName(ED_25519);
  Gson gson = GsonInstance.INSTANCE.get();
  JsonParser parser = new JsonParser();
  FieldValidator fieldValidator = new FieldValidator();


  // BRVS keys count = User keys count
  int PROPORTION = 2;

  Ed25519Sha3 crypto = new Ed25519Sha3();

  SubscriptionStrategy subscriptionStrategy = new WaitForTerminalStatus(
      Arrays.asList(
          TxStatus.STATELESS_VALIDATION_FAILED,
          TxStatus.STATEFUL_VALIDATION_FAILED,
          TxStatus.COMMITTED,
          TxStatus.REJECTED,
          TxStatus.UNRECOGNIZED
      )
  );

  static String getTxAccountId(final Transaction transaction) {
    return transaction.getPayload().getReducedPayload().getCreatorAccountId();
  }

  static List<String> hexHash(TransactionBatch transactionBatch) {
    return transactionBatch
        .getTransactionList()
        .stream()
        .map(ValidationUtils::hexHash)
        .collect(Collectors.toList());
  }

  static String hexHash(Transaction transaction) {
    return Utils.toHex(Utils.hash(transaction));
  }

  static String hexHash(Block block) {
    return Utils.toHex(Utils.hash(block));
  }

  static String readKey(String keyPath) throws IOException {
    return new String(Files.readAllBytes(Paths.get(keyPath)));
  }

  static KeyPair generateOrImportFirstKeypair(String path) throws IOException {
    return generateOrImportKeypairs(1, path).get(0);
  }

  static List<KeyPair> generateOrImportKeypairs(String amount, String path) throws IOException {
    return generateOrImportKeypairs(Integer.parseInt(amount), path);
  }

  static List<KeyPair> generateOrImportKeypairs(int amount, String path) throws IOException {
    if (amount < 1) {
      throw new IllegalArgumentException("Amount must be more than zero");
    }
    final Path keysPath = Paths.get(path);
    List<KeyPair> keyPairs = new ArrayList<>(amount);
    Files.createDirectories(keysPath);
    for (int i = 0; i < amount; i++) {
      final Path pubPath = keysPath.resolve("key" + i + ".pub");
      final Path privPath = keysPath.resolve("key" + i + ".priv");
      if (pubPath.toFile().exists() && privPath.toFile().exists()) {
        final String pubKey = readKey(pubPath.toString());
        final String privKey = readKey(privPath.toString());
        keyPairs.add(Utils.parseHexKeypair(pubKey, privKey));
      } else {
        final KeyPair keypair = generateKeypair();
        keyPairs.add(keypair);
        Files.write(pubPath,
            DatatypeConverter.printHexBinary(keypair.getPublic().getEncoded()).getBytes());
        Files.write(privPath,
            DatatypeConverter.printHexBinary(keypair.getPrivate().getEncoded()).getBytes());
      }
    }
    return ImmutableList.copyOf(keyPairs);
  }

  static RMQConfig loadLocalRmqConfig() {
    return ConfigsKt.loadRawLocalConfigs("rmq", RMQConfig.class, "rmq.properties");
  }

  static KeyPair generateKeypair() {
    return crypto.generateKeypair();
  }

  /**
   * Escapes symbols reserved in JSON so it can be used in Iroha
   */
  static String irohaEscape(String str) {
    return str.replace("\"", IROHA_FRIENDLY_QUOTE)
        .replace("\n", IROHA_FRIENDLY_NEW_LINE);
  }

  /**
   * Reverse changes of 'irohaEscape'
   */
  static String irohaUnEscape(String str) {
    return str.replace(IROHA_FRIENDLY_QUOTE, "\"")
        .replace(IROHA_FRIENDLY_NEW_LINE, "\n");
  }

  static EdDSAPublicKey derivePublicKey(EdDSAPrivateKey privateKey) {
    return new EdDSAPublicKey(new EdDSAPublicKeySpec(privateKey.getA(), EdDSASpec));
  }

  static <T> T advancedQueryAccountDetails(
      QueryAPI queryAPI,
      String accountId,
      String setterAccountId,
      String key,
      Class<T> type) {
    final JsonElement jsonElement = parser.parse(
        queryAPI.getAccountDetails(
            accountId,
            setterAccountId,
            key
        )
    ).getAsJsonObject().get(setterAccountId);
    return jsonElement == null ? null : gson.fromJson(
        Utils.irohaUnEscape(jsonElement.getAsJsonObject().get(key).getAsString()),
        type
    );
  }
}
