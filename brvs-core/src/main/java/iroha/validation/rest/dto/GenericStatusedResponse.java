/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import iroha.validation.exception.BrvsErrorCode;

/**
 * Represent non specific response containing with {@link ResponseStatus} included
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class GenericStatusedResponse {

  public static final GenericStatusedResponse SUCCESS = new GenericStatusedResponse(
      ResponseStatus.SUCCESS
  );

  protected final ResponseStatus status;

  public GenericStatusedResponse(ResponseStatus status) {
    this.status = status;
  }

  public GenericStatusedResponse(BrvsErrorCode code, String message) {
    this(new ResponseStatus(code, message));
  }

  public GenericStatusedResponse() {
    this(ResponseStatus.SUCCESS);
  }
}
