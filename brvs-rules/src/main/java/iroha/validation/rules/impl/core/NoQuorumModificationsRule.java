/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.core;

import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;

public class NoQuorumModificationsRule implements Rule {

  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    if (transaction.getPayload().getReducedPayload().getCommandsList().stream()
        .anyMatch(Command::hasSetAccountQuorum)) {
      return ValidationResult.REJECTED("User is not allowed to change their quorum");
    }
    return ValidationResult.VALIDATED;
  }
}
