/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.sora;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.util.StringUtils;

public class XorWithdrawalLimitRule implements Rule {

  public static final String ASSET_ID = "xor#sora";

  private final String withdrawalAccountId;
  private final AtomicReference<XorWithdrawalLimitRemainder> xorWithdrawalLimitRemainder;
  private final boolean isDisabled;

  public XorWithdrawalLimitRule(String withdrawalAccountId,
      AtomicReference<XorWithdrawalLimitRemainder> xorWithdrawalLimitRemainder,
      boolean isDisabled) {
    if (StringUtils.isEmpty(withdrawalAccountId)) {
      throw new IllegalArgumentException(
          "Withdrawal account ID must not be neither null nor empty"
      );
    }
    Objects.requireNonNull(
        xorWithdrawalLimitRemainder,
        "Xor withdrawal limit remainder reference must not be null"
    );

    this.withdrawalAccountId = withdrawalAccountId;
    this.xorWithdrawalLimitRemainder = xorWithdrawalLimitRemainder;
    this.isDisabled = isDisabled;
  }

  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    final BigDecimal sum = transaction.getPayload().getReducedPayload().getCommandsList()
        .stream()
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .filter(cmd -> withdrawalAccountId.equals(cmd.getDestAccountId()))
        .filter(cmd -> ASSET_ID.equals(cmd.getAssetId()))
        .map(TransferAsset::getAmount)
        .map(BigDecimal::new)
        .reduce(BigDecimal::add)
        .orElse(BigDecimal.ZERO);
    if (sum.compareTo(BigDecimal.ZERO) == 0) {
      return ValidationResult.VALIDATED;
    }
    // > 0 XOR to withdraw
    if (this.isDisabled) {
      return ValidationResult.REJECTED("Sorry, withdrawals are temporarily disabled");
    }

    final long createdTime = transaction.getPayload().getReducedPayload().getCreatedTime();
    final long timestampDue = xorWithdrawalLimitRemainder.get().timestampDue;
    if (createdTime > timestampDue) {
      return ValidationResult
          .REJECTED("Transaction may have been sent from the future, got " + createdTime
              + " time created, current limit expires at " + timestampDue);
    }
    return processWithdrawalSum(sum);
  }

  private ValidationResult processWithdrawalSum(BigDecimal sum) {
    final XorWithdrawalLimitRemainder currentLimit = this.xorWithdrawalLimitRemainder.get();
    final BigDecimal amountRemaining = currentLimit.amountRemaining;
    final BigDecimal difference = amountRemaining.subtract(sum);
    if (difference.compareTo(BigDecimal.ZERO) < 0) {
      return ValidationResult.REJECTED(
          "Withdrawal amount exceeds the limit. Got " + sum.toPlainString() +
              ", limit " + amountRemaining.toPlainString()
      );
    }
    // don't care about the result actually
    this.xorWithdrawalLimitRemainder.compareAndSet(
        currentLimit,
        new XorWithdrawalLimitRemainder(
            difference,
            currentLimit.timestampDue
        )
    );
    return ValidationResult.VALIDATED;
  }

  public static class XorWithdrawalLimitRemainder {

    private BigDecimal amountRemaining;

    private final long timestampDue;

    public XorWithdrawalLimitRemainder(BigDecimal amountRemaining, long timestampDue) {
      Objects.requireNonNull(amountRemaining, "Amount remaining must not be null");
      if (timestampDue < 0) {
        throw new IllegalArgumentException("Timestamp must be positive, got " + timestampDue);
      }
      this.amountRemaining = amountRemaining;
      this.timestampDue = timestampDue;
    }

    public BigDecimal getAmountRemaining() {
      return amountRemaining;
    }

    public void setAmountRemaining(BigDecimal amountRemaining) {
      this.amountRemaining = amountRemaining;
    }

    public long getTimestampDue() {
      return timestampDue;
    }
  }
}
