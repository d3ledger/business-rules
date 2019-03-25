package iroha.validation.service.impl;

import iroha.validation.config.ValidationServiceContext;
import iroha.validation.service.ValidationService;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.validators.Validator;
import java.util.Collection;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ValidationServiceImpl implements ValidationService {

  private static Logger logger = LoggerFactory.getLogger(ValidationServiceImpl.class);

  private final Collection<Validator> validators;
  private final TransactionProvider transactionProvider;
  private final TransactionSigner transactionSigner;
  private final RegistrationProvider registrationProvider;
  private final BrvsData brvsData;
  private final boolean isRoot;

  public ValidationServiceImpl(ValidationServiceContext validationServiceContext) {
    Objects.requireNonNull(validationServiceContext, "ValidationServiceContext must not be null");

    this.validators = validationServiceContext.getValidators();
    this.transactionProvider = validationServiceContext.getTransactionProvider();
    this.transactionSigner = validationServiceContext.getTransactionSigner();
    this.registrationProvider = validationServiceContext.getRegistrationProvider();
    this.brvsData = validationServiceContext.getBrvsData();
    this.isRoot = validationServiceContext.isRoot();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void verifyTransactions() {
    // if this instance is not the first in the network
    if (!isRoot) {
      registerBrvs();
    }
    registerExistentAccounts();
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

  private void registerExistentAccounts() {
    logger.info("Going to register existent user accounts in BRVS: " + brvsData.getHostname());
    final Iterable<String> userAccounts;
    try {
      userAccounts = registrationProvider.getUserAccounts();
    } catch (Exception e) {
      logger.warn("Couldn't query existing accounts. Please add it manually", e);
      return;
    }
    userAccounts.forEach(account -> {
      try {
        registrationProvider.register(account);
      } catch (Exception e) {
        logger.warn("Couldn't add existing account " + account + " Please add it manually", e);
      }
    });
  }

  private void registerBrvs() {
    logger.info("Trying to register new brvs instance (self)");
    registrationProvider.addBrvsInstance(brvsData);
  }
}
