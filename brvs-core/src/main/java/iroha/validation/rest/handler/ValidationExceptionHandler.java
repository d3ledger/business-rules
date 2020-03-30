/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest.handler;

import static iroha.validation.exception.BrvsErrorCode.FIELD_VALIDATION_ERROR;

import iroha.validation.rest.dto.GenericStatusedResponse;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import jp.co.soramitsu.iroha.java.ValidationException;

@Provider
public class ValidationExceptionHandler implements ExceptionMapper<ValidationException> {

  @Override
  public Response toResponse(ValidationException exception) {
    return Response.ok(
        new GenericStatusedResponse(
            FIELD_VALIDATION_ERROR,
            exception.getMessage()
        )
    ).build();
  }
}
