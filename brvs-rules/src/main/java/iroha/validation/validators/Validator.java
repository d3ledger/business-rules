/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.validators;

import iroha.protocol.TransactionOuterClass;
import iroha.validation.verdict.ValidationResult;

/**
 * Validator interface. Used as a facade abstraction. Assume a validator can use complex logic
 * involving many rules so validators are not parametrized
 */
public interface Validator {

  /**
   * Method for transaction validation
   *
   * @param transaction Iroha proto transaction
   * @return {@link ValidationResult} corresponding to validating outcome
   */
  ValidationResult validate(TransactionOuterClass.Transaction transaction);
}
