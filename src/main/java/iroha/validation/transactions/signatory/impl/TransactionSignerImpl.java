package iroha.validation.transactions.signatory.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import java.security.KeyPair;
import java.util.Objects;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TransactionSignerImpl implements TransactionSigner {

  private final IrohaAPI irohaAPI;
  private final KeyPair keyPair;
  private final TransactionVerdictStorage transactionVerdictStorage;

  @Autowired
  public TransactionSignerImpl(IrohaAPI irohaAPI,
      KeyPair keyPair,
      TransactionVerdictStorage transactionVerdictStorage) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    Objects.requireNonNull(keyPair, "Keypair must not be null");
    Objects.requireNonNull(keyPair, "TransactionVerdictStorage must not be null");

    this.irohaAPI = irohaAPI;
    this.keyPair = keyPair;
    this.transactionVerdictStorage = transactionVerdictStorage;
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
    transactionVerdictStorage.markTransactionValidated(Utils.toHex(Utils.hash(validatedTx)));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void reject(Transaction transaction, String reason) {
    transactionVerdictStorage.markTransactionRejected(Utils.toHex(Utils.hash(transaction)), reason);
  }
}
