/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.exception;

public class BrvsException extends RuntimeException {

  private final BrvsErrorCode code;

  public BrvsException(String message, BrvsErrorCode code) {
    super(message);
    this.code = code;
  }

  public BrvsException(String message, Throwable cause, BrvsErrorCode code) {
    super(message, cause);
    this.code = code;
  }

  public BrvsErrorCode getCode() {
    return code;
  }
}
