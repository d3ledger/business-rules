/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.service;

/**
 * Validation service interface. Used as a facade abstraction
 */
public interface ValidationService {

  /**
   * Method for transaction verification
   */
  void verifyTransactions();
}
