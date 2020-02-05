/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules;

import iroha.protocol.TransactionOuterClass;
import iroha.validation.verdict.ValidationResult;

/**
 * Rule interface
 */
public interface Rule {

  /**
   * Method for checking transaction rule satisfiability
   *
   * @param transaction Iroha proto transaction
   * @return {@link ValidationResult} corresponding to satisfiability checking outcome
   */
  ValidationResult isSatisfiedBy(TransactionOuterClass.Transaction transaction);
}
