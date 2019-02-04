package iroha.validation.rules;

import iroha.protocol.TransactionOuterClass;

/**
 * Rule interface
 */
public interface Rule {

  /**
   * Method for checking transaction rule satisfiability
   *
   * @param transaction Iroha proto transaction
   * @return <code>true</code> if the transaction satisfies rule;
   * <code>false</code> otherwise
   */
  boolean isSatisfiedBy(TransactionOuterClass.Transaction transaction);
}
