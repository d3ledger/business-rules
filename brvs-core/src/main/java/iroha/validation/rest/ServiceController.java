/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Singleton
@Path("")
public class ServiceController {

  private static final long SECONDS = 90L;
  private static final String DOWN = "{\"status\":\"DOWN\"}";
  private static final String UP = "{\"status\":\"UP\"}";

  @Inject
  private AtomicReference<Instant> lastQueryingTimestamp;

  @GET
  @Path("/actuator/health")
  @Produces(MediaType.APPLICATION_JSON)
  public Response isHealthy() {
    final Instant instant = lastQueryingTimestamp.get();
    if (instant != null && instant.plusSeconds(SECONDS).isBefore(Instant.now())) {
      Response.ok(DOWN).build();
    }
    return Response.ok(UP).build();
  }
}
