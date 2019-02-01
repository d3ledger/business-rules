package iroha.validation.validators;

import iroha.protocol.TransactionOuterClass;

/**
 * Validator interface. Used as a facade abstraction. Assume a validator can use complex logic
 * involving many rules so validators are not parametrized
 */
public interface Validator {

  /**
   * Method for transaction validation
   *
   * @param transaction Iroha proto transaction
   * @return <code>true</code> if the transaction satisfies all described rules by validator;
   * <code>false</code> otherwise
   */
  Boolean validate(TransactionOuterClass.Transaction transaction);
}
