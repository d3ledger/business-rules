/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.lykke;

import static jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Rule implementation to call lykke KYC endpoint and check if it's status is correct for a
 * transaction creator
 */
public class LykkeRule implements Rule {

  private static final String LYKKE_SUCCESS_STATUS = "\"Ok\"";

  private final String url;

  public LykkeRule(String url) {
    this.url = url;
  }

  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    try {
      return performCheck(
          transaction
              .getPayload()
              .getReducedPayload()
              .getCreatorAccountId()
      );
    } catch (Exception e) {
      return ValidationResult.REJECTED("Lykke interaction failed: " + e.getMessage());
    }
  }

  private ValidationResult performCheck(String accountId) throws IOException {
    URL requestUrl = new URL(url + accountId.split(accountIdDelimiter)[0]);
    HttpURLConnection urlConnection = (HttpURLConnection) requestUrl.openConnection();
    final int responseCode = urlConnection.getResponseCode();
    if (responseCode != 200) {
      return ValidationResult.REJECTED(
          "Got wrong response from Lykke: "
              + responseCode + " " + urlConnection.getResponseMessage()
      );
    }
    final String status = getMessage(urlConnection);
    if (status.equals(LYKKE_SUCCESS_STATUS)) {
      return ValidationResult.VALIDATED;
    } else {
      return ValidationResult.REJECTED(
          "Lykke KYC status of " + accountId + " was: " + status + ". Expected: "
              + LYKKE_SUCCESS_STATUS);
    }
  }

  private String getMessage(HttpURLConnection urlConnection) throws IOException {
    StringBuilder response = new StringBuilder();
    try (BufferedReader in = new BufferedReader(
        new InputStreamReader(urlConnection.getInputStream()))) {
      String inputLine;
      while ((inputLine = in.readLine()) != null) {
        response.append(inputLine);
      }
    }
    return response.toString();
  }
}
