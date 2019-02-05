package iroha.validation.transactions.signatory;

import iroha.protocol.TransactionOuterClass;

public interface TransactionSigner {

  /**
   * Method for signing validated transaction and sending it to Iroha peer
   *
   * @param transaction Iroha proto transaction
   */
  void signAndSend(TransactionOuterClass.Transaction transaction);
}
