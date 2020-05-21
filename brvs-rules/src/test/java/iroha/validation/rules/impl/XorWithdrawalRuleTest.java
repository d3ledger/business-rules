/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl;

import static iroha.validation.rules.impl.sora.XorWithdrawalLimitRule.ASSET_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.sora.XorWithdrawalLimitRule;
import iroha.validation.rules.impl.sora.XorWithdrawalLimitRule.XorWithdrawalLimitRemainder;
import iroha.validation.verdict.Verdict;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class XorWithdrawalRuleTest {

  private Transaction transaction;
  private BigDecimal amount;
  private TransferAsset transferAsset;
  private List<Command> commands;
  private Rule rule;

  private void init(boolean moreThanValid) {
    final String withdrawalAccountId = "withdrawal@users";
    amount = new BigDecimal("100");

    // transfer mock
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    transferAsset = mock(TransferAsset.class);

    when(transferAsset.getSrcAccountId()).thenReturn("user@users");
    when(transferAsset.getDestAccountId()).thenReturn(withdrawalAccountId);
    when(transferAsset.getDescription()).thenReturn("description");
    when(transferAsset.getAssetId()).thenReturn(ASSET_ID);
    when(transferAsset.getAmount()).thenReturn(amount.toPlainString());

    final Command command = mock(Command.class);

    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset()).thenReturn(transferAsset);

    commands = Collections.singletonList(command);

    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
    )
        .thenReturn(commands);

    rule = new XorWithdrawalLimitRule(
        withdrawalAccountId,
        new AtomicReference<>(
            new XorWithdrawalLimitRemainder(
                moreThanValid ? BigDecimal.ONE : amount,
                System.currentTimeMillis()
            )
        )
    );
  }

  /**
   * @given {@link XorWithdrawalLimitRule} instance with limit of asset amount equal to 100
   * @when {@link Transaction} with {@link Command TransferAsset} command of 100 "asset" passed
   * @then {@link XorWithdrawalLimitRule} is satisfied by such {@link Transaction}
   */
  @Test
  void correctTransferTxVolumeRuleTest() {
    init(false);

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link XorWithdrawalLimitRule} instance with limit of asset amount equal to 1
   * @when {@link Transaction} with {@link Command TransferAsset} command of 100 "asset" hasn't passed
   * @then {@link XorWithdrawalLimitRule} is not satisfied by such {@link Transaction}
   */
  @Test
  void otherAssetTransferTxVolumeRuleTest() {
    init(true);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }
}
