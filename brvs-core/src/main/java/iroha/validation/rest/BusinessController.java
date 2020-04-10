/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest;

import static iroha.validation.exception.BrvsErrorCode.REGISTRATION_FAILED;
import static iroha.validation.utils.ValidationUtils.fieldValidator;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import iroha.validation.exception.BrvsException;
import iroha.validation.rest.dto.GenericStatusedResponse;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.verdict.ValidationResult;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Singleton
@Path("/brvs/rest/v1")
public class BusinessController {

  @Inject
  private RegistrationProvider registrationProvider;
  @Inject
  private TransactionVerdictStorage verdictStorage;

  @GET
  @Path("/status/{txHash}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getStatus(@PathParam("txHash") String hash) {
    ValidationResult transactionVerdict = verdictStorage.getTransactionVerdict(hash);
    if (transactionVerdict == null) {
      transactionVerdict = ValidationResult.UNKNOWN;
    }
    return Response.ok(new ValidationResultResponse(transactionVerdict)).build();
  }

  @POST
  @Path("/register/{accountId}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response register(@PathParam("accountId") String accountId) {
    try {
      registrationProvider.register(accountId);
    } catch (InterruptedException e) {
      throw new BrvsException(e.getMessage(), e, REGISTRATION_FAILED);
    }
    return Response.ok(GenericStatusedResponse.SUCCESS).build();
  }

  @POST
  @Path("/isRegistered")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response isRegistered(String jsonBody) {
    final String accountId = ValidationUtils.gson
        .fromJson(jsonBody, AccountIdJsonWrapper.class)
        .getAccountId();
    fieldValidator.checkAccountId(accountId);
    final boolean isRegistered = registrationProvider.getRegisteredAccounts()
        .stream()
        .anyMatch(registeredAccount -> registeredAccount.equals(accountId));
    return Response.ok(new AccountRegisteredResponse(isRegistered)).build();
  }

  /**
   * A simple wrapper class for (de)serializing JSONed account id in Iroha
   */
  private static class AccountIdJsonWrapper {

    private String accountId;

    String getAccountId() {
      return accountId;
    }
  }

  /**
   * A simple wrapper class for serializing proper boolean result response
   */
  @JsonAutoDetect(fieldVisibility = Visibility.ANY)
  private static class AccountRegisteredResponse extends GenericStatusedResponse {

    private final boolean registered;

    AccountRegisteredResponse(boolean registered) {
      this.registered = registered;
    }
  }

  /**
   * A simple wrapper class for serializing proper {@link ValidationResult} response
   */
  @JsonAutoDetect(fieldVisibility = Visibility.ANY)
  private static class ValidationResultResponse extends GenericStatusedResponse {

    private final ValidationResult validationResult;

    public ValidationResultResponse(ValidationResult validationResult) {
      this.validationResult = validationResult;
    }
  }
}
