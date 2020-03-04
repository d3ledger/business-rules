/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.service.impl;

import static com.d3.commons.util.ThreadUtilKt.createPrettySingleThreadPool;
import static iroha.validation.utils.ValidationUtils.hexHash;

import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.internal.functions.Functions;
import io.reactivex.schedulers.Schedulers;
import iroha.validation.config.ValidationServiceContext;
import iroha.validation.rules.RuleMonitor;
import iroha.validation.service.ValidationService;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.AccountManager;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.validators.Validator;
import iroha.validation.verdict.ValidationResult;
import iroha.validation.verdict.Verdict;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core BRVS service abstraction impl
 */
public class ValidationServiceImpl implements ValidationService, Closeable {

  private static Logger logger = LoggerFactory.getLogger(ValidationServiceImpl.class);

  private final Validator validator;
  private final TransactionProvider transactionProvider;
  private final TransactionSigner transactionSigner;
  private final RegistrationProvider registrationProvider;
  private final BrvsData brvsData;
  private final RuleMonitor ruleMonitor;
  private final Scheduler mainScheduler = Schedulers.from(createPrettySingleThreadPool(
      "brvs", "main"
  ));
  private final Scheduler scheduler = Schedulers.from(Executors.newCachedThreadPool());

  public ValidationServiceImpl(ValidationServiceContext validationServiceContext) {
    Objects.requireNonNull(validationServiceContext, "ValidationServiceContext must not be null");

    this.validator = validationServiceContext.getValidator();
    this.transactionProvider = validationServiceContext.getTransactionProvider();
    this.transactionSigner = validationServiceContext.getTransactionSigner();
    this.registrationProvider = validationServiceContext.getRegistrationProvider();
    this.brvsData = validationServiceContext.getBrvsData();
    this.ruleMonitor = validationServiceContext.getRuleMonitor();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void verifyTransactions() {
    registerExistentAccounts();
    ruleMonitor.monitorUpdates();
    transactionProvider.getPendingTransactionsStreaming()
        .observeOn(mainScheduler)
        .flatMap(transactionBatch ->
            Observable.fromCallable(() -> processTransactionBatch(transactionBatch))
                .subscribeOn(scheduler)
        )
        .subscribe(Functions.emptyConsumer(),
            throwable -> logger.error("Unknown exception was thrown: ", throwable)
        );
  }

  /**
   * Calls relevant validators and rules for each passed user transaction
   *
   * @param transactionBatch user related {@link TransactionBatch}
   * @return the same {@link TransactionBatch} that was passed as an argument
   */
  private TransactionBatch processTransactionBatch(TransactionBatch transactionBatch) {
    final List<String> hex = hexHash(transactionBatch);
    try {
      logger.info("Got transactions to validate: {}", hex);
      final ValidationResult validationResult = validator.validate(transactionBatch);
      if (Verdict.VALIDATED != validationResult.getStatus()) {
        final String reason = validationResult.getReason();
        transactionSigner.rejectAndSend(transactionBatch, reason);
        logger
            .info("Transactions {} have been rejected by the service. Reason: {}", hex, reason);
      } else {
        transactionSigner.signAndSend(transactionBatch);
        logger.info("Transactions {} have been successfully validated and signed", hex);
      }
    } catch (Exception exception) {
      logger.error("Error during " + hex + " transaction validation: ", exception);
      System.exit(1);
    }
    return transactionBatch;
  }

  /**
   * Reads Iroha details containing a list of accounts that should be checked by BRVS
   */
  private void registerExistentAccounts() {
    logger.info("Going to register existent user accounts in BRVS: {}", brvsData.getHostname());
    final Set<String> userAccounts;
    try {
      userAccounts = registrationProvider.getUserAccounts();
    } catch (Exception e) {
      logger.warn("Couldn't query existing accounts. Please add them manually", e);
      return;
    }
    try {
      registrationProvider.register(userAccounts);
    } catch (Exception e) {
      logger.error("Couldn't register some of existing accounts", e);
    }
  }

  @Override
  public void close() throws IOException {
    mainScheduler.shutdown();
    transactionProvider.close();
    ((AccountManager) registrationProvider).close();
  }
}
