package iroha.validation.config;

import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.transactions.storage.TransactionProvider;
import iroha.validation.validators.Validator;
import java.security.KeyPair;
import java.util.Collection;

public class ValidationServiceContext {

  private Collection<Validator> validators;
  private TransactionProvider transactionProvider;
  private TransactionSigner transactionSigner;
  private KeyPair keyPair;

  public ValidationServiceContext(
      Collection<Validator> validators,
      TransactionProvider transactionProvider,
      TransactionSigner transactionSigner, KeyPair keyPair) {
    this.validators = validators;
    this.transactionProvider = transactionProvider;
    this.transactionSigner = transactionSigner;
    this.keyPair = keyPair;
  }

  public Collection<Validator> getValidators() {
    return validators;
  }

  public TransactionProvider getTransactionProvider() {
    return transactionProvider;
  }

  public TransactionSigner getTransactionSigner() {
    return transactionSigner;
  }

  public KeyPair getKeyPair() {
    return keyPair;
  }
}
