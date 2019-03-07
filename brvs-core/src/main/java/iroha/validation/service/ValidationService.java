package iroha.validation.service;

/**
 * Validation service interface. Used as a facade abstraction
 */
public interface ValidationService {

  /**
   * Method for transaction verification
   */
  void verifyTransactions();
}
