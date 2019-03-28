package iroha.validation.utils;

import com.google.common.collect.ImmutableList;
import iroha.protocol.TransactionOuterClass.Transaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.Utils;

public interface ValidationUtils {

  Ed25519Sha3 crypto = new Ed25519Sha3();

  static String getTxAccountId(final Transaction transaction) {
    return transaction.getPayload().getReducedPayload().getCreatorAccountId();
  }

  static String hexHash(Transaction transaction) {
    return Utils.toHex(Utils.hash(transaction));
  }

  static KeyPair readKeyPairFromFiles(String pubKeyPath, String privKeyPath) {
    try {
      final String pubKeyHex = readKey(pubKeyPath);
      final String privKeyHex = readKey(privKeyPath);
      return Utils.parseHexKeypair(pubKeyHex, privKeyHex);
    } catch (IOException e) {
      throw new IllegalArgumentException("Couldn't read key files", e);
    }
  }

  static String readKey(String keyPath) throws IOException {
    return new String(Files.readAllBytes(Paths.get(keyPath)));
  }

  static List<KeyPair> generateKeypairs(int amount) {
    if (amount < 1) {
      throw new IllegalArgumentException("Amount must be more than zero");
    }
    List<KeyPair> keyPairs = new ArrayList<>(amount);
    for (int i = 0; i < amount; i++) {
      keyPairs.add(generateKeypair());
    }
    return ImmutableList.copyOf(keyPairs);
  }

  static KeyPair generateKeypair() {
    return crypto.generateKeypair();
  }
}
