/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest.handler;

import iroha.validation.exception.BrvsException;
import iroha.validation.rest.dto.GenericStatusedResponse;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class BrvsExceptionHandler implements ExceptionMapper<BrvsException> {

  @Override
  public Response toResponse(BrvsException exception) {
    return Response.ok(
        new GenericStatusedResponse(
            exception.getCode(),
            exception.getMessage()
        )
    ).build();
  }
}
