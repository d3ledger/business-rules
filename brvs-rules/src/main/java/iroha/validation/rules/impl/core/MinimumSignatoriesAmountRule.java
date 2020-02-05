/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.core;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.RemoveSignatory;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.List;
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
    return checkRemoveSignatories(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasRemoveSignatory)
        .map(Command::getRemoveSignatory)
        .collect(Collectors.toList())
    );
  }

  private ValidationResult checkRemoveSignatories(List<RemoveSignatory> removeSignatories) {
    for (RemoveSignatory removeSignatory : removeSignatories) {
      final String accountId = removeSignatory.getAccountId();
      final int keysCount = queryAPI.getSignatories(accountId).getKeysCount();
      // user and brvs keys
      if (keysCount - 2 < amount) {
        return ValidationResult
            .REJECTED("User " + accountId + " cannot have less than " + amount + " signatories");
      }
    }
    return ValidationResult.VALIDATED;
  }
}
