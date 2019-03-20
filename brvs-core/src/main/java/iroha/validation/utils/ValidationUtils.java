package iroha.validation.utils;

import iroha.protocol.TransactionOuterClass.Transaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import jp.co.soramitsu.iroha.java.Utils;

public interface ValidationUtils {

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
}
