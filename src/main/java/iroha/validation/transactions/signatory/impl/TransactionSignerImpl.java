package iroha.validation.transactions.signatory.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.signatory.TransactionSigner;
import java.security.KeyPair;
import java.util.Objects;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TransactionSignerImpl implements TransactionSigner {

  private final IrohaAPI irohaAPI;
  private final KeyPair keyPair;

  @Autowired
  public TransactionSignerImpl(IrohaAPI irohaAPI, KeyPair keyPair) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    Objects.requireNonNull(keyPair, "Keypair must not be null");

    this.irohaAPI = irohaAPI;
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
