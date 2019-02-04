package iroha.validation.rules.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;

public class SampleRule implements Rule {

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSatisfiedBy(Transaction transaction) {
    return true;
  }
}
