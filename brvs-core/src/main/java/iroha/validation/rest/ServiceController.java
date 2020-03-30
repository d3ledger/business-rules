/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Singleton
@Path("")
public class ServiceController {

  @GET
  @Path("/actuator/health")
  @Produces(MediaType.APPLICATION_JSON)
  public Response isHealthy() {
    return Response.ok("{\"status\":\"UP\"}").build();
  }
}
