/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.storage.impl.mongo;

import iroha.validation.verdict.ValidationResult;

public class MongoVerdict {

  private String txHash;
  private ValidationResult result;

  public MongoVerdict() {
  }

  public MongoVerdict(String txHash, ValidationResult result) {
    this.txHash = txHash;
    this.result = result;
  }

  public String getTxHash() {
    return txHash;
  }

  public void setTxHash(String txHash) {
    this.txHash = txHash;
  }

  public ValidationResult getResult() {
    return result;
  }

  public void setResult(ValidationResult result) {
    this.result = result;
  }
}
