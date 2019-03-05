package iroha.validation.transactions.signatory.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import java.security.KeyPair;
import java.util.Objects;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Utils;

public class TransactionSignerImpl implements TransactionSigner {

  private static final KeyPair fakeKeyPair = Utils.parseHexKeypair(
      "0000000000000000000000000000000000000000000000000000000000000000",
      "0000000000000000000000000000000000000000000000000000000000000000"
  );

  private final IrohaAPI irohaAPI;
  private final KeyPair keyPair;
  private final TransactionVerdictStorage transactionVerdictStorage;

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
    transactionVerdictStorage.markTransactionValidated(ValidationUtils.hexHash(transaction));
    irohaAPI.transactionSync(jp.co.soramitsu.iroha.java.Transaction
        .parseFrom(transaction)
        .sign(keyPair)
        .build());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rejectAndSend(Transaction transaction, String reason) {
    transactionVerdictStorage.markTransactionRejected(ValidationUtils.hexHash(transaction), reason);
    irohaAPI.transactionSync(jp.co.soramitsu.iroha.java.Transaction
        .parseFrom(transaction)
        .sign(fakeKeyPair)
        .build());
  }
}
