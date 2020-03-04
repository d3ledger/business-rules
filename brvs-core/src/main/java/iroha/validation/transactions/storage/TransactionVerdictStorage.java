/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.storage;

import iroha.validation.verdict.ValidationResult;
import java.io.Closeable;

public interface TransactionVerdictStorage extends Closeable {

  /**
   * Method for indicating if hash provided is contained in a storage
   *
   * @param txHash transaction hash
   */
  boolean isHashPresentInStorage(String txHash);

  /**
   * Method for saving (new) transaction verdict as pending to a storage
   *
   * @param txHash transaction hash
   * @return true if marked successfully, false otherwise
   */
  boolean markTransactionPending(String txHash);

  /**
   * Method for saving transaction verdict as validated successfully to a storage
   *
   * @param txHash transaction hash
   */
  void markTransactionValidated(String txHash);

  /**
   * Method for saving transaction verdict as rejected by a reason to a storage
   *
   * @param txHash transaction hash
   * @param reason reason
   */
  void markTransactionRejected(String txHash, String reason);

  /**
   * Method for saving transaction verdict as failed by a reason to a storage
   *
   * @param txHash transaction hash
   * @param reason reason
   */
  void markTransactionFailed(String txHash, String reason);

  /**
   * Method for retrieving transaction validation verdict
   *
   * @param txHash transaction hash
   */
  ValidationResult getTransactionVerdict(String txHash);
}
