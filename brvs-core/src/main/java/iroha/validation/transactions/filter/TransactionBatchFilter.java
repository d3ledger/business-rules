/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.filter;

import iroha.validation.transactions.TransactionBatch;

/**
 * Interface for the transaction filters to indicate if a particular transaction should be processed
 * even before storing it
 */
public interface TransactionBatchFilter {

  /**
   * Filters transaction
   *
   * @param transactionBatch Iroha transaction batch
   * @return true - if the transaction should be processed, false - otherwise
   */
  boolean filter(TransactionBatch transactionBatch);

}
