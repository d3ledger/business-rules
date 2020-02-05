/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.validators;

import iroha.protocol.TransactionOuterClass;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.Set;

/**
 * Validator interface. Used as a facade abstraction. Assume a validator can use complex logic
 * involving many rules so validators are not parametrized
 */
public interface Validator {

  /**
   * Method for transaction validation
   *
   * @param transactions Iroha proto transactions
   * @return {@link ValidationResult} corresponding to validating outcome
   */
  ValidationResult validate(Iterable<TransactionOuterClass.Transaction> transactions);

  /**
   * Adds a rule to the rules collection processed by the validator
   *
   * @param name {@link String Name} of the rule
   * @param rule {@link Rule} to be added
   * @return previous {@link Rule} associated with the name
   */
  Rule putRule(String name, Rule rule);

  /**
   * Removes a rule from the rules collection processed by the validator
   *
   * @param name {@link String Name} of the rule to be removed
   * @return previous {@link Rule} associated with the name
   */
  Rule removeRule(String name);

  /**
   * Reads all the rule names currently contained in the validator
   *
   * @return {@link Set} of {@link String names}
   */
  Set<String> getRuleNames();
}
