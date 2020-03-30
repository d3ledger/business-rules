/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest.dto;

import iroha.validation.exception.BrvsErrorCode;

/**
 * Class representing http response status with respect to {@link BrvsErrorCode}
 */
public class ResponseStatus {

  private static final String SUCCESS_STATUS_MESSAGE = "Success";
  public static ResponseStatus SUCCESS = new ResponseStatus(
      BrvsErrorCode.OK,
      SUCCESS_STATUS_MESSAGE
  );

  protected final BrvsErrorCode code;

  protected final String message;

  public ResponseStatus(BrvsErrorCode code, String message) {
    this.code = code;
    this.message = message;
  }

  public BrvsErrorCode getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }
}
