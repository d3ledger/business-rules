/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.verdict;

public class ValidationResult {

  public static final ValidationResult UNKNOWN = new ValidationResult(Verdict.UNKNOWN,
      "Transaction is not found");
  public static final ValidationResult PENDING = new ValidationResult(Verdict.PENDING,
      "Transaction is being checked");
  public static final ValidationResult VALIDATED = new ValidationResult(Verdict.VALIDATED);

  public static ValidationResult REJECTED(String reason) {
    return new ValidationResult(Verdict.REJECTED, reason);
  }

  public static ValidationResult FAILED(String reason) {
    return new ValidationResult(Verdict.FAILED, reason);
  }

  private Verdict status;

  private String reason;

  private ValidationResult(Verdict status) {
    this(status, "");
  }

  private ValidationResult(Verdict status, String reason) {
    this.status = status;
    this.reason = reason;
  }

  public Verdict getStatus() {
    return status;
  }

  public String getReason() {
    return reason;
  }

  // Following things are made for BSON (MongoDB Serializer)

  protected ValidationResult() {
  }

  public void setStatus(Verdict status) {
    this.status = status;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
