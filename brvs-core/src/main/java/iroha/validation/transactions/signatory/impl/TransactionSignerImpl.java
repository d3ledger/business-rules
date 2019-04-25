package iroha.validation.transactions.signatory.impl;

import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.java.detail.BuildableAndSignable;
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
  public void signAndSend(TransactionBatch transactionBatch) {
    for (Transaction transaction : transactionBatch) {
      transactionVerdictStorage.markTransactionValidated(ValidationUtils.hexHash(transaction));
    }
    if (isCreatedByBrvs(transactionBatch)) {
      sendBrvsTransactionBatch(transactionBatch, brvsAccountKeyPair);
    } else {
      sendUserTransactionBatch(transactionBatch);
    }
  }

  private boolean isCreatedByBrvs(TransactionBatch transactionBatch) {
    return StreamSupport.stream(transactionBatch.spliterator(), false)
        .anyMatch(transaction -> brvsAccountId
            .equals(transaction.getPayload().getReducedPayload().getCreatorAccountId()));
  }

  private void sendUserTransactionBatch(TransactionBatch transactionBatch) {
    final List<Transaction> transactions = new ArrayList<>(
        transactionBatch.getTransactionList().size()
    );
    for (Transaction transaction : transactionBatch) {
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
      transactions.add(parsedTransaction.build());
    }
    if (transactions.size() > 1) {
      irohaAPI.transactionListSync(transactions);
    } else {
      irohaAPI.transactionSync(transactions.get(0));
    }
  }

  private void sendRejectedUserTransaction(TransactionBatch transactionBatch) {
    final List<Transaction> transactions = new ArrayList<>(
        transactionBatch.getTransactionList().size()
    );
    for (Transaction transaction : transactionBatch) {
      final jp.co.soramitsu.iroha.java.Transaction parsedTransaction = jp.co.soramitsu.iroha.java.Transaction
          .parseFrom(transaction);
      final int signaturesCount = transaction.getSignaturesCount();

      // Since we assume brvs signatures must be as many as users
      for (int i = 0; i < signaturesCount; i++) {
        parsedTransaction.sign(fakeKeyPair);
      }
      transactions.add(parsedTransaction.build());
    }
    if (transactions.size() > 1) {
      irohaAPI.transactionListSync(transactions);
    } else {
      irohaAPI.transactionSync(transactions.get(0));
    }
  }

  private void sendBrvsTransactionBatch(TransactionBatch transactionBatch, KeyPair keyPair) {
    for (Transaction transaction : transactionBatch) {
      for (Command command : transaction.getPayload().getReducedPayload().getCommandsList()) {
        // Do not sign set acc quorum about user account if its time is synchronized
        // There will be a multisig transaction sync on time
        // Instead of many fully signed same transactions
        if (transaction.getPayload().getReducedPayload().getCreatedTime() % 1000000 == 0 &&
            (
                (command.hasSetAccountQuorum() && !brvsAccountId
                    .equals(command.getSetAccountQuorum().getAccountId()))
                    ||
                    // Do not sign setAccDetails (user quorum) for same reason
                    (command.hasSetAccountDetail())
            )
        ) {
          return;
        }
      }
    }

    final List<Transaction> transactionList = transactionBatch.getTransactionList();
    if (transactionList.size() > 1) {
      irohaAPI.transactionListSync(
          transactionList
              .stream()
              .map(jp.co.soramitsu.iroha.java.Transaction::parseFrom)
              .map(transaction -> transaction.sign(brvsAccountKeyPair))
              .map(BuildableAndSignable::build)
              .collect(Collectors.toList())
      );
    } else {
      irohaAPI.transactionSync(
          jp.co.soramitsu.iroha.java.Transaction
              .parseFrom(transactionList.get(0))
              .sign(brvsAccountKeyPair)
              .build()
      );
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rejectAndSend(TransactionBatch transactionBatch, String reason) {
    for (Transaction transaction : transactionBatch) {
      transactionVerdictStorage.markTransactionRejected(
          ValidationUtils.hexHash(transaction),
          reason
      );
    }
    if (isCreatedByBrvs(transactionBatch)) {
      sendBrvsTransactionBatch(transactionBatch, fakeKeyPair);
    } else {
      sendRejectedUserTransaction(transactionBatch);
    }
  }
}
