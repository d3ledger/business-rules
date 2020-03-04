/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.storage.impl.dummy;

import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DummyMemoryTransactionVerdictStorage implements TransactionVerdictStorage {

  private final Map<String, ValidationResult> validationResultMap = new ConcurrentHashMap<>();

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isHashPresentInStorage(String txHash) {
    return validationResultMap.containsKey(txHash.toUpperCase());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean markTransactionPending(String txHash) {
    validationResultMap.putIfAbsent(txHash.toUpperCase(), ValidationResult.PENDING);
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionValidated(String txHash) {
    validationResultMap.put(txHash.toUpperCase(), ValidationResult.VALIDATED);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionRejected(String txHash, String reason) {
    validationResultMap.put(txHash.toUpperCase(), ValidationResult.REJECTED(reason));
  }

  @Override
  public void markTransactionFailed(String txHash, String reason) {
    validationResultMap.put(txHash.toUpperCase(), ValidationResult.FAILED(reason));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult getTransactionVerdict(String txHash) {
    return validationResultMap.get(txHash.toUpperCase());
  }

  @Override
  public void close() {
    // nothing to close
  }
}
