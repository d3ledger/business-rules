package iroha.validation.service.impl;

import iroha.validation.config.ValidationServiceContext;
import iroha.validation.service.ValidationService;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.validators.Validator;
import java.util.Collection;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

@Component
@ComponentScan("iroha.validation")
public class ValidationServiceImpl implements ValidationService {

  private static Logger logger = LoggerFactory.getLogger(ValidationServiceImpl.class);

  private Collection<Validator> validators;
  private TransactionProvider transactionProvider;
  private TransactionSigner transactionSigner;

  @Autowired
  public ValidationServiceImpl(ValidationServiceContext validationServiceContext) {
    Objects.requireNonNull(validationServiceContext, "ValidationServiceContext must not be null");

    this.validators = validationServiceContext.getValidators();
    this.transactionProvider = validationServiceContext.getTransactionProvider();
    this.transactionSigner = validationServiceContext.getTransactionSigner();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void verifyTransactions() {
    transactionProvider.getPendingTransactionsStreaming().subscribe(transaction ->
        {
          boolean verdict = true;
          final String hex = ValidationUtils.hexHash(transaction);
          logger.info("Got transaction to validate: " + hex);
          for (Validator validator : validators) {
            if (!validator.validate(transaction)) {
              final String canonicalName = validator.getClass().getCanonicalName();
              transactionSigner.rejectAndSend(transaction, canonicalName);
              logger.info(
                  "Transaction has been rejected by the service. Failed validator: " + canonicalName);
              verdict = false;
              break;
            }
          }
          if (verdict) {
            transactionSigner.signAndSend(transaction);
            logger.info("Transaction has been successfully validated and signed");
          }
        }
    );
  }

  @Override
  public void registerAccount(String accountId) {
    transactionProvider.register(accountId);
  }
}