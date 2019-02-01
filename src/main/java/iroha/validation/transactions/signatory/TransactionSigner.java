package iroha.validation.transactions.signatory;

import iroha.protocol.TransactionOuterClass;
import java.security.KeyPair;

public interface TransactionSigner {

  /**
   * Method for signing validated transaction and sending it to Iroha peer
   *
   * @param transaction Iroha proto transaction
   * @param keyPair {@link KeyPair} to sign with
   */
  void signAndSend(TransactionOuterClass.Transaction transaction, KeyPair keyPair);
}
