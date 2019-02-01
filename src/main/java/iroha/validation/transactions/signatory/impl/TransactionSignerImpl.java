package iroha.validation.transactions.signatory.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.signatory.TransactionSigner;
import java.security.KeyPair;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

public class TransactionSignerImpl implements TransactionSigner {

  /**
   * {@inheritDoc}
   */
  @Override
  public void signAndSend(Transaction transaction, KeyPair keyPair) {
    throw new NotImplementedException();
  }
}
