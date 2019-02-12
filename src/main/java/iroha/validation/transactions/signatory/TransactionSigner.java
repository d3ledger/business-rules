package iroha.validation.transactions.signatory;

import iroha.protocol.TransactionOuterClass;

public interface TransactionSigner {

  /**
   * Method for signing validated transaction and sending it to Iroha peer
   *
   * @param transaction Iroha proto transaction
   */
  void signAndSend(TransactionOuterClass.Transaction transaction);

  /**
   * Method for rejecting transaction with a reason
   *
   * @param transaction Iroha proto transaction
   * @param reason reason
   */
  void rejectAndSend(TransactionOuterClass.Transaction transaction, String reason);
}
