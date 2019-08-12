/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.service.impl;

import iroha.validation.verdict.ValidationResult;
import java.util.List;

public class ValidationResultWrapper {

  private final List<String> hexHashes;
  private final ValidationResult validationResult;
  private final Exception exception;

  public ValidationResultWrapper(List<String> hexHashes,
      ValidationResult validationResult,
      Exception exception) {

    this.hexHashes = hexHashes;
    this.validationResult = validationResult;
    this.exception = exception;
  }

  public List<String> getHexHashes() {
    return hexHashes;
  }

  public ValidationResult getValidationResult() {
    return validationResult;
  }

  public Exception getException() {
    return exception;
  }
}
