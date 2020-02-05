/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.validators.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.validators.Validator;
import iroha.validation.verdict.ValidationResult;
import iroha.validation.verdict.Verdict;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class SimpleAggregationValidator implements Validator {

  private final Map<String, Rule> rules;

  public SimpleAggregationValidator() {
    this(Collections.emptyMap());
  }

  public SimpleAggregationValidator(Map<String, Rule> rules) {
    this.rules = rules;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized ValidationResult validate(Iterable<Transaction> transactions) {
    for (Transaction transaction : transactions) {
      for (Rule rule : rules.values()) {
        final ValidationResult validationResult = rule.isSatisfiedBy(transaction);
        if (validationResult.getStatus().equals(Verdict.REJECTED)) {
          return validationResult;
        }
      }
    }
    return ValidationResult.VALIDATED;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Rule putRule(String name, Rule rule) {
    return rules.put(name, rule);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Rule removeRule(String name) {
    return rules.remove(name);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Set<String> getRuleNames() {
    return rules.keySet();
  }
}
