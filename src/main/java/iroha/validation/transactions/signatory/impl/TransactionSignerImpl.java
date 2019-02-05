package iroha.validation.transactions.signatory.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.signatory.TransactionSigner;
import java.security.KeyPair;
import jp.co.soramitsu.iroha.java.IrohaAPI;

public class TransactionSignerImpl implements TransactionSigner {

  private IrohaAPI irohaAPI;
  private KeyPair keyPair;

  public TransactionSignerImpl(String host, int port, KeyPair keyPair) {
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
