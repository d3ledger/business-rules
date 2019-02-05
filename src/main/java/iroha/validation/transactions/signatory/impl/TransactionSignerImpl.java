package iroha.validation.transactions.signatory.impl;

import com.google.common.base.Strings;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.signatory.TransactionSigner;
import java.security.KeyPair;
import java.util.Objects;
import jp.co.soramitsu.iroha.java.IrohaAPI;

public class TransactionSignerImpl implements TransactionSigner {

  private final IrohaAPI irohaAPI;
  private final KeyPair keyPair;

  public TransactionSignerImpl(String host, int port, KeyPair keyPair) {
    if (Strings.isNullOrEmpty(host)) {
      throw new IllegalArgumentException("Host must not be neither null or empty");
    }
    Objects.requireNonNull(keyPair, "Keypair must not be null");

    this.irohaAPI = new IrohaAPI(host, port);
    this.keyPair = keyPair;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void signAndSend(Transaction transaction) {
    Transaction validatedTx = jp.co.soramitsu.iroha.java.Transaction
        .parseFrom(transaction)
        .sign(keyPair)
        .build();

    irohaAPI.transactionSync(validatedTx);
  }
}
