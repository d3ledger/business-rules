/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.config;

import iroha.validation.rules.RuleMonitor;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.validators.Validator;
import java.util.Objects;

/**
 * Simple data structure used to aggregate all significant modules of the brvs in a one place
 */
public class ValidationServiceContext {

  private final Validator validator;
  private final TransactionProvider transactionProvider;
  private final TransactionSigner transactionSigner;
  private final RegistrationProvider registrationProvider;
  private final BrvsData brvsData;
  private final RuleMonitor ruleMonitor;

  public ValidationServiceContext(
      Validator validator,
      TransactionProvider transactionProvider,
      TransactionSigner transactionSigner,
      RegistrationProvider registrationProvider,
      BrvsData brvsData,
      RuleMonitor ruleMonitor) {
    Objects.requireNonNull(validator, "Validator must not be null");
    Objects.requireNonNull(transactionProvider, "Transaction provider must not be null");
    Objects.requireNonNull(transactionSigner, "Transaction signer must not be null");
    Objects.requireNonNull(registrationProvider, "Registration provider must not be null");
    Objects.requireNonNull(brvsData, "BRVS data must not be null");
    Objects.requireNonNull(ruleMonitor, "Rule monitor must not be null");

    this.validator = validator;
    this.transactionProvider = transactionProvider;
    this.transactionSigner = transactionSigner;
    this.registrationProvider = registrationProvider;
    this.brvsData = brvsData;
    this.ruleMonitor = ruleMonitor;
  }

  public Validator getValidator() {
    return validator;
  }

  public TransactionProvider getTransactionProvider() {
    return transactionProvider;
  }

  public TransactionSigner getTransactionSigner() {
    return transactionSigner;
  }

  public RegistrationProvider getRegistrationProvider() {
    return registrationProvider;
  }

  public BrvsData getBrvsData() {
    return brvsData;
  }

  public RuleMonitor getRuleMonitor() {
    return ruleMonitor;
  }
}
