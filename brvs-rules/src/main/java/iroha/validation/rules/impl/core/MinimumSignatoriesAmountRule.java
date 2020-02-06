/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.core;

import iroha.protocol.Commands.AddSignatory;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.RemoveSignatory;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.QueryAPI;

public class MinimumSignatoriesAmountRule implements Rule {

  private final int amount;
  private final QueryAPI queryAPI;

  public MinimumSignatoriesAmountRule(String amount, QueryAPI queryAPI) {
    this.amount = Integer.parseInt(amount);
    if (queryAPI == null) {
      throw new IllegalArgumentException("Query API must not be null");
    }
    this.queryAPI = queryAPI;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    final List<Command> commands = transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList();

    final Map<String, Long> keyBalance = commands
        .stream()
        .filter(Command::hasAddSignatory)
        .map(Command::getAddSignatory)
        .map(AddSignatory::getAccountId)
        .collect(
            Collectors.groupingBy(
                Function.identity(),
                Collectors.counting()
            )
        );

    commands
        .stream()
        .filter(Command::hasRemoveSignatory)
        .map(Command::getRemoveSignatory)
        .map(RemoveSignatory::getAccountId)
        .forEach(accountId -> {
          if (!keyBalance.containsKey(accountId)) {
            keyBalance.put(accountId, 0L);
          }
          keyBalance.put(accountId, keyBalance.get(accountId) - 1);
        });

    return checkRemoveSignatories(keyBalance);
  }

  private ValidationResult checkRemoveSignatories(Map<String, Long> keyBalance) {
    for (String account : keyBalance.keySet()){
      final int keysCount = queryAPI.getSignatories(account).getKeysCount();
      // user and brvs keys
      if (keysCount + keyBalance.get(account) < amount) {
        return ValidationResult
            .REJECTED("User " + account + " cannot have less than " + amount + " signatories");
      }
    }
    return ValidationResult.VALIDATED;
  }
}
