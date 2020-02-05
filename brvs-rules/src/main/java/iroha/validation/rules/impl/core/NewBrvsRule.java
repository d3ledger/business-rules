/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.QueryAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewBrvsRule implements Rule {

  private static final Logger logger = LoggerFactory.getLogger(NewBrvsRule.class);
  private static final JsonParser parser = new JsonParser();

  private QueryAPI queryAPI;
  private String brvsAccountId;

  public NewBrvsRule(QueryAPI queryAPI) {
    this.queryAPI = queryAPI;
    this.brvsAccountId = queryAPI.getAccountId();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    try {
      final Set<String> brvsPubKeys = getPubKeys();
      final Set<String> newKeys = transaction
          .getPayload()
          .getReducedPayload()
          .getCommandsList()
          .stream()
          .filter(Command::hasAddSignatory)
          .filter(command -> command.getAddSignatory().getAccountId().equals(brvsAccountId))
          .map(command -> command.getAddSignatory().getPublicKey())
          .collect(Collectors.toSet());
      for (String key : newKeys) {
        if (!brvsPubKeys.contains(key)) {
          return ValidationResult.REJECTED(
              "Key " + key + " is not known as BRVS pubkey"
          );
        }
      }
      return ValidationResult.VALIDATED;
    } catch (Exception e) {
      logger.error("Couldn't read brvs keys from Iroha", e);
      return ValidationResult.REJECTED("Couldn't read brvs keys from Iroha. " + e.getMessage());
    }
  }

  private Set<String> getPubKeys() {
    Set<String> resultSet = new HashSet<>();
    JsonElement rootNode = parser
        .parse(queryAPI
            .getAccount(brvsAccountId)
            .getAccount()
            .getJsonData()
        );
    rootNode.getAsJsonObject().entrySet().forEach(accountSetter ->
        accountSetter
            .getValue()
            .getAsJsonObject()
            .entrySet()
            .forEach(entry -> resultSet.add(entry.getKey()))
    );
    return resultSet;
  }
}
