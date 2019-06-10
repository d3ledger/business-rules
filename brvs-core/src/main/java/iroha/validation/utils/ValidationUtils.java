/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.utils;

import com.d3.commons.config.ConfigsKt;
import com.d3.commons.config.RMQConfig;
import com.google.common.collect.ImmutableList;
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
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.java.subscription.SubscriptionStrategy;
import jp.co.soramitsu.iroha.java.subscription.WaitForTerminalStatus;

public interface ValidationUtils {

  Ed25519Sha3 crypto = new Ed25519Sha3();

  SubscriptionStrategy subscriptionStrategy = new WaitForTerminalStatus(
      Arrays.asList(
          TxStatus.STATELESS_VALIDATION_FAILED,
          TxStatus.STATEFUL_VALIDATION_FAILED,
          TxStatus.COMMITTED,
          TxStatus.MST_EXPIRED,
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
}
