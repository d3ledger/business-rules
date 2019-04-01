package iroha.validation.utils;

import com.google.common.collect.ImmutableList;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.TransactionOuterClass.Transaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.DatatypeConverter;
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

  static String hexHash(Block block) {
    return Utils.toHex(Utils.hash(block));
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

  static KeyPair generateKeypair() {
    return crypto.generateKeypair();
  }
}
