/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.RemoveSignatory;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.Utils;

public class RestrictedKeysRule implements Rule {

  private Set<String> restrictedKeys;

  public RestrictedKeysRule(Collection<KeyPair> restrictedKeys) {
    this.restrictedKeys =
        restrictedKeys
            .stream()
            .map(KeyPair::getPublic)
            .map(PublicKey::getEncoded)
            .map(Utils::toHex)
            .map(String::toUpperCase)
            .collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    return checkRemovals(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasRemoveSignatory)
        .map(Command::getRemoveSignatory)
        .map(RemoveSignatory::getPublicKey)
        .map(String::toUpperCase)
        .filter(removeSignatory -> restrictedKeys.contains(removeSignatory))
        .collect(Collectors.toList())
    );
  }

  private ValidationResult checkRemovals(List<String> removeSignatoryList) {
    if (removeSignatoryList.isEmpty()) {
      return ValidationResult.VALIDATED;
    }
    return ValidationResult.REJECTED("Client is not able to remove signatories: "
        + removeSignatoryList);
  }
}
