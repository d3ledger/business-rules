package iroha.validation.service.impl;

import iroha.validation.config.ValidationServiceContext;
import iroha.validation.service.ValidationService;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.transactions.storage.TransactionProvider;
import iroha.validation.validators.Validator;
import java.security.KeyPair;
import java.util.Collection;
import java.util.Objects;

public class ValidationServiceImpl implements ValidationService {

  private Collection<Validator> validators;
  private TransactionProvider transactionProvider;
  private TransactionSigner transactionSigner;
  private KeyPair keyPair;

  public ValidationServiceImpl(ValidationServiceContext validationServiceContext) {
    Objects.requireNonNull(validationServiceContext, "ValidationServiceContext must not be null");

    this.validators = validationServiceContext.getValidators();
    this.transactionProvider = validationServiceContext.getTransactionProvider();
    this.transactionSigner = validationServiceContext.getTransactionSigner();
    this.keyPair = validationServiceContext.getKeyPair();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void verifyTransactions() {
    transactionProvider.getPendingTransactionsStreaming().subscribe(transaction ->
        {
          boolean verdict = validators.stream().allMatch(validator -> validator.validate(transaction));
          if (verdict) {
            transactionSigner.signAndSend(transaction, keyPair);
          }
        }
    );
  }
}
