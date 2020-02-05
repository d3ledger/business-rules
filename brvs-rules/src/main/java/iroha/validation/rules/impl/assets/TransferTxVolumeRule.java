/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.assets;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

public class TransferTxVolumeRule implements Rule {

  private String asset;
  private BigDecimal limit;

  public TransferTxVolumeRule(String asset, BigDecimal limit) {
    this.asset = asset;
    this.limit = limit;
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
        .filter(transfer -> transfer.getAssetId().equals(asset))
        .collect(Collectors.toList())
    );
  }

  private ValidationResult checkTransfers(List<TransferAsset> transfers) {
    for (TransferAsset transfer : transfers) {
      final String amount = transfer.getAmount();
      if (new BigDecimal(amount).compareTo(limit) > 0) {
        return ValidationResult.REJECTED(
            "Transfer exceeds the limit. Value: " + amount + ", Limit: " + limit
        );
      }
    }
    return ValidationResult.VALIDATED;
  }
}
