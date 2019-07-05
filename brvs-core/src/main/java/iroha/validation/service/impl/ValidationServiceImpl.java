/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.service.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.config.ValidationServiceContext;
import iroha.validation.service.ValidationService;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.validators.Validator;
import iroha.validation.verdict.ValidationResult;
import iroha.validation.verdict.Verdict;
import java.util.Collection;
import java.util.List;
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

  public ValidationServiceImpl(ValidationServiceContext validationServiceContext) {
    Objects.requireNonNull(validationServiceContext, "ValidationServiceContext must not be null");

    this.validators = validationServiceContext.getValidators();
    this.transactionProvider = validationServiceContext.getTransactionProvider();
    this.transactionSigner = validationServiceContext.getTransactionSigner();
    this.registrationProvider = validationServiceContext.getRegistrationProvider();
    this.brvsData = validationServiceContext.getBrvsData();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void verifyTransactions() {
    registerExistentAccounts();
    transactionProvider.getPendingTransactionsStreaming().subscribe(transactionBatch ->
        {
          boolean verdict = true;
          final List<String> hex = ValidationUtils.hexHash(transactionBatch);
          logger.info("Got transactions to validate: " + hex);
          for (Transaction transaction : transactionBatch) {
            for (Validator validator : validators) {
              final ValidationResult validationResult = validator.validate(transaction);
              if (Verdict.VALIDATED != validationResult.getStatus()) {
                final String reason = validationResult.getReason();
                transactionSigner.rejectAndSend(transactionBatch, reason);
                verdict = false;
                break;
              }
            }
          }
          if (verdict) {
            transactionSigner.signAndSend(transactionBatch);
          }
        },
        throwable -> logger.error("Error during transaction validation: ", throwable)
    );
  }

  private void registerExistentAccounts() {
    logger.info("Going to register existent user accounts in BRVS: " + brvsData.getHostname());
    final Iterable<String> userAccounts;
    try {
      userAccounts = registrationProvider.getUserAccounts();
    } catch (Exception e) {
      logger.warn("Couldn't query existing accounts. Please add them manually", e);
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
}
