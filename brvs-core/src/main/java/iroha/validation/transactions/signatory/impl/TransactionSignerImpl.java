package iroha.validation.transactions.signatory.impl;

import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import java.security.KeyPair;
import java.util.List;
import java.util.Objects;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Utils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

public class TransactionSignerImpl implements TransactionSigner {

  private static final KeyPair fakeKeyPair = Utils.parseHexKeypair(
      "0000000000000000000000000000000000000000000000000000000000000000",
      "0000000000000000000000000000000000000000000000000000000000000000"
  );

  private final IrohaAPI irohaAPI;
  private final String brvsAccountId;
  private final KeyPair brvsAccountKeyPair;
  private final List<KeyPair> keyPairs;
  private final TransactionVerdictStorage transactionVerdictStorage;

  public TransactionSignerImpl(IrohaAPI irohaAPI,
      List<KeyPair> keyPairs,
      String brvsAccountId,
      KeyPair brvsAccountKeyPair,
      TransactionVerdictStorage transactionVerdictStorage) {
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (CollectionUtils.isEmpty(keyPairs)) {
      throw new IllegalArgumentException("Keypairs must not be neither null nor empty");
    }
    if (StringUtils.isEmpty(brvsAccountId)) {
      throw new IllegalArgumentException("Brvs account id must not be neither null nor empty");
    }
    Objects.requireNonNull(brvsAccountKeyPair, "Brvs key pair must not be null");
    Objects.requireNonNull(keyPairs, "TransactionVerdictStorage must not be null");

    this.irohaAPI = irohaAPI;
    this.brvsAccountId = brvsAccountId;
    this.brvsAccountKeyPair = brvsAccountKeyPair;
    this.keyPairs = keyPairs;
    this.transactionVerdictStorage = transactionVerdictStorage;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void signAndSend(Transaction transaction) {
    transactionVerdictStorage.markTransactionValidated(ValidationUtils.hexHash(transaction));
    if (brvsAccountId.equals(transaction.getPayload().getReducedPayload().getCreatorAccountId())) {
      sendBrvsTransaction(transaction);
    } else {
      sendUserTransaction(transaction);
    }
  }

  private void sendUserTransaction(Transaction transaction) {
    final jp.co.soramitsu.iroha.java.Transaction parsedTransaction = jp.co.soramitsu.iroha.java.Transaction
        .parseFrom(transaction);
    final int signaturesCount = transaction.getSignaturesCount();
    if (signaturesCount > keyPairs.size()) {
      throw new IllegalStateException(
          "Too many user signatures in the transaction: " + signaturesCount +
              ". Key list size is " + keyPairs.size());
    }
    // Since we assume brvs signatures must be as many as users
    for (int i = 0; i < signaturesCount; i++) {
      parsedTransaction.sign(keyPairs.get(i));
    }
    irohaAPI.transactionSync(parsedTransaction.build());
  }

  private void sendBrvsTransaction(Transaction transaction) {
    for (Command command : transaction.getPayload().getReducedPayload().getCommandsList()) {
      // Do not sign set acc quorum about user account
      // There will be a multisig transaction sync on time
      // Instead of many fully signed same transactions
      if (command.hasSetAccountQuorum() && !brvsAccountId
          .equals(command.getSetAccountQuorum().getAccountId())) {
        return;
      }
    }

    irohaAPI.transactionSync(
        jp.co.soramitsu.iroha.java.Transaction
            .parseFrom(transaction)
            .sign(brvsAccountKeyPair)
            .build()
    );
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
        .build()
    );
  }
}
