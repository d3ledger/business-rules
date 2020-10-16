/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.filter.sora;

import static iroha.validation.rules.impl.billing.BillingRule.XOR_ASSET_ID;

import iroha.protocol.Commands.Command;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.transactions.filter.TransactionBatchFilter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Filters out XOR transfer transactions
 */
public class XorTransfersTemporaryIgnoringFilter implements TransactionBatchFilter {

  //disabled by default
  private final AtomicBoolean enabled = new AtomicBoolean();

  public synchronized void enable() {
    enabled.set(true);
  }

  public synchronized void disable() {
    enabled.set(false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean filter(TransactionBatch transactionBatch) {
    return !enabled.get() ||
        transactionBatch
            .stream()
            .noneMatch(
                transaction -> transaction.getPayload()
                    .getReducedPayload()
                    .getCommandsList()
                    .stream()
                    .filter(Command::hasTransferAsset)
                    .map(Command::getTransferAsset)
                    .anyMatch(transferAsset -> transferAsset.getAssetId().equals(XOR_ASSET_ID))
            );
  }
}
