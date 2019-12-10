/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.byacco;

import static jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter;

import com.google.common.base.Strings;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ByaccoDomainInternalAssetRule implements Rule {

  private String assetId;
  private Set<String> domains;

  public ByaccoDomainInternalAssetRule(String assetId, String domains) {
    if (Strings.isNullOrEmpty(assetId)) {
      throw new IllegalArgumentException("Asset id string must not be null nor empty");
    }
    if (Strings.isNullOrEmpty(domains)) {
      throw new IllegalArgumentException("Domains string must not be null nor empty");
    }

    this.assetId = assetId;
    this.domains = Arrays.stream(domains.split(",")).collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    return checkTransfers(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .filter(transfer -> transfer.getAssetId().equals(assetId))
        .collect(Collectors.toList())
    );
  }

  private ValidationResult checkTransfers(List<TransferAsset> transfers) {
    for (TransferAsset transfer : transfers) {
      final String transferAssetId = transfer.getAssetId();
      final String destinationDomain = transfer.getDestAccountId().split(accountIdDelimiter)[1];
      if (assetId.equals(transferAssetId) && !domains.contains(destinationDomain)) {
        return ValidationResult.REJECTED(
            "Transfer of " + assetId + " can be only within " + domains + " domains"
        );
      }
    }
    return ValidationResult.VALIDATED;
  }
}
