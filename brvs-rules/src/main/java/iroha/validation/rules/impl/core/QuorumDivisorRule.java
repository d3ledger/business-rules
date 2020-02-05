/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.core;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.SetAccountQuorum;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.List;
import java.util.stream.Collectors;

public class QuorumDivisorRule implements Rule {

  private final int divisor;

  public QuorumDivisorRule(String divisor) {
    this.divisor = Integer.parseInt(divisor);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    return checkQuorums(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasSetAccountQuorum)
        .map(Command::getSetAccountQuorum)
        .collect(Collectors.toList())
    );
  }

  private ValidationResult checkQuorums(List<SetAccountQuorum> setAccountQuorumList) {
    for (SetAccountQuorum setAccountQuorum : setAccountQuorumList) {
      if (setAccountQuorum.getQuorum() % divisor != 0) {
        return ValidationResult
            .REJECTED("User quorum must be divided without a remainder by " + divisor);
      }
    }
    return ValidationResult.VALIDATED;
  }
}
