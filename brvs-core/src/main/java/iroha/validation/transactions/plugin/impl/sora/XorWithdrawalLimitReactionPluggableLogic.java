/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl.sora;

import static iroha.validation.rules.impl.sora.XorWithdrawalLimitRule.ASSET_ID;
import static iroha.validation.utils.ValidationUtils.sendWithLastResponseWaiting;

import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.CompareAndSetAccountDetail;
import iroha.protocol.Commands.SetAccountDetail;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.protocol.TransactionOuterClass.Transaction.Payload;
import iroha.protocol.TransactionOuterClass.Transaction.Payload.ReducedPayload;
import iroha.validation.rules.impl.sora.XorWithdrawalLimitRule.XorWithdrawalLimitRemainder;
import iroha.validation.transactions.core.provider.RegistrationProvider;
import iroha.validation.transactions.plugin.PluggableLogic;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.Utils;
import kotlin.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Newly withdrawn assets must be accounted
 */
public class XorWithdrawalLimitReactionPluggableLogic extends
    PluggableLogic<Pair<Map<String, String>, BigDecimal>> {

  private static final Logger logger = LoggerFactory
      .getLogger(XorWithdrawalLimitReactionPluggableLogic.class);
  public static final String LIMIT_TIME_KEY = "withdrawal_time_update";
  public static final String LIMIT_AMOUNT_KEY = "withdrawal_limit";

  private final QueryAPI queryAPI;
  private final IrohaQueryHelper irohaQueryHelper;
  private final String limitHolderAccount;
  private final String limitSetterAccount;
  private final AtomicReference<XorWithdrawalLimitRemainder> xorWithdrawalLimitRemainder;
  private final String withdrawalAccountId;
  private final RegistrationProvider registrationProvider;

  public XorWithdrawalLimitReactionPluggableLogic(
      QueryAPI queryAPI,
      IrohaQueryHelper irohaQueryHelper,
      String limitHolderAccount,
      String limitSetterAccount,
      AtomicReference<XorWithdrawalLimitRemainder> xorWithdrawalLimitRemainder,
      String withdrawalAccountId,
      RegistrationProvider registrationProvider) {

    Objects.requireNonNull(
        queryAPI,
        "QueryAPI must not be null"
    );
    Objects.requireNonNull(
        irohaQueryHelper,
        "IrohaQueryHelper must not be null"
    );
    if (StringUtils.isEmpty(limitHolderAccount)) {
      throw new IllegalArgumentException(
          "Limit holder account ID must not be neither null nor empty"
      );
    }
    if (StringUtils.isEmpty(limitSetterAccount)) {
      throw new IllegalArgumentException(
          "Limit setter account ID must not be neither null nor empty"
      );
    }
    Objects.requireNonNull(
        xorWithdrawalLimitRemainder,
        "Xor withdrawal limit remainder reference must not be null"
    );
    Objects.requireNonNull(
        registrationProvider,
        "Registration provider reference must not be null"
    );
    if (StringUtils.isEmpty(withdrawalAccountId)) {
      throw new IllegalArgumentException(
          "Withdrawal account ID must not be neither null nor empty"
      );
    }

    this.queryAPI = queryAPI;
    this.irohaQueryHelper = irohaQueryHelper;
    this.limitHolderAccount = limitHolderAccount;
    this.limitSetterAccount = limitSetterAccount;
    this.xorWithdrawalLimitRemainder = xorWithdrawalLimitRemainder;
    this.withdrawalAccountId = withdrawalAccountId;
    this.registrationProvider = registrationProvider;

    requestIrohaLimitsAndPerformUpdate();
  }

  private void requestIrohaLimitsAndPerformUpdate() {
    final long timestampDue = getTimestampFrom(
        irohaQueryHelper,
        this.limitSetterAccount
    );

    final long brvsLastTimestampDue = getTimestampFrom(
        irohaQueryHelper,
        this.queryAPI.getAccountId()
    );

    final BigDecimal brvsRemaining = getRelevantBrvsRemaining();

    final BigDecimal amountRemaining = timestampDue == brvsLastTimestampDue ?
        brvsRemaining : getLimitFrom(
        irohaQueryHelper,
        this.limitSetterAccount
    );

    updateWithdrawalLimits(
        new XorWithdrawalLimitRemainder(
            amountRemaining,
            timestampDue
        )
    );
  }

  private BigDecimal getRelevantBrvsRemaining() {
    return getLimitFrom(
        irohaQueryHelper,
        this.queryAPI.getAccountId()
    );
  }

  private long getTimestampFrom(IrohaQueryHelper irohaQueryHelper, String setterAccountId) {
    return Long.parseLong(
        getDetailedValueFrom(
            irohaQueryHelper,
            setterAccountId,
            LIMIT_TIME_KEY
        )
    );
  }

  private BigDecimal getLimitFrom(IrohaQueryHelper irohaQueryHelper, String setterAccountId) {
    return new BigDecimal(
        getDetailedValueFrom(
            irohaQueryHelper,
            setterAccountId,
            LIMIT_AMOUNT_KEY
        )
    );
  }

  private String getDetailedValueFrom(IrohaQueryHelper irohaQueryHelper,
      String setterAccountId,
      String key) {
    return irohaQueryHelper.getAccountDetails(
        this.limitHolderAccount,
        setterAccountId,
        key
    ).get().orElse("0");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Pair<Map<String, String>, BigDecimal> filterAndTransform(Block block) {
    final List<Transaction> transactions = block.getBlockV1().getPayload().getTransactionsList();
    if (transactions == null || transactions.isEmpty()) {
      return new Pair<>(Collections.emptyMap(), BigDecimal.ZERO);
    }
    final Map<String, String> newDetails = transactions.stream()
        .map(Transaction::getPayload)
        .map(Payload::getReducedPayload)
        .filter(it -> limitSetterAccount.equals(it.getCreatorAccountId()))
        .map(ReducedPayload::getCommandsList)
        .flatMap(Collection::stream)
        .filter(command -> command.hasSetAccountDetail() || command.hasCompareAndSetAccountDetail())
        .map(command -> {
          if (command.hasSetAccountDetail()) {
            final SetAccountDetail setAccountDetail = command.getSetAccountDetail();
            return new AccountDetailsData(
                setAccountDetail.getAccountId(),
                setAccountDetail.getKey(),
                setAccountDetail.getValue()
            );
          } else {
            final CompareAndSetAccountDetail compareAndSetAccountDetail = command
                .getCompareAndSetAccountDetail();
            return new AccountDetailsData(
                compareAndSetAccountDetail.getAccountId(),
                compareAndSetAccountDetail.getKey(),
                compareAndSetAccountDetail.getValue()
            );
          }
        })
        .filter(accountDetailsData -> limitHolderAccount.equals(accountDetailsData.accountId))
        .collect(
            Collectors.toMap(
                accountDetailsData -> accountDetailsData.key,
                accountDetailsData -> accountDetailsData.value
            )
        );

    // to care only about new transactions if there is an external update
    final long lastUpdateTime = Optional.ofNullable(newDetails.get(LIMIT_TIME_KEY))
        .map(Long::parseLong).orElse(0L);

    final BigDecimal withdrawalsAmount = transactions.stream()
        .map(Transaction::getPayload)
        .map(Payload::getReducedPayload)
        .filter(pld -> pld.getCreatedTime() >= lastUpdateTime)
        .map(ReducedPayload::getCommandsList)
        .flatMap(Collection::stream)
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .filter(command -> ASSET_ID.equals(command.getAssetId()))
        .filter(command -> registrationProvider.isRegistered(command.getSrcAccountId()))
        .filter(command -> withdrawalAccountId.equals(command.getDestAccountId()))
        .map(TransferAsset::getAmount)
        .map(BigDecimal::new)
        .reduce(BigDecimal::add)
        .orElse(BigDecimal.ZERO);

    return new Pair<>(newDetails, withdrawalsAmount);
  }

  private void updateWithdrawalLimits(XorWithdrawalLimitRemainder xorWithdrawalLimitRemainder) {
    if (xorWithdrawalLimitRemainder.getAmountRemaining().signum() == -1) {
      xorWithdrawalLimitRemainder.setAmountRemaining(BigDecimal.ZERO);
    }
    this.xorWithdrawalLimitRemainder.set(xorWithdrawalLimitRemainder);
    logger.info("Updated withdrawal limits: " +
        xorWithdrawalLimitRemainder.getAmountRemaining().toPlainString() +
        " due " + xorWithdrawalLimitRemainder.getTimestampDue()
    );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void applyInternal(Pair<Map<String, String>, BigDecimal> processableObject) {
    final Map<String, String> newDetails = processableObject.getFirst();
    if (newDetails.containsKey(LIMIT_TIME_KEY)) {
      logger.info("Got external withdrawal limits details update");
      if (newDetails.containsKey(LIMIT_AMOUNT_KEY)) {
        final String newTimeDue = newDetails.get(LIMIT_TIME_KEY);
        final String newLimitValue = newDetails.get(LIMIT_AMOUNT_KEY);
        final Transaction transaction = jp.co.soramitsu.iroha.java.Transaction
            .builder(queryAPI.getAccountId())
            .setAccountDetail(limitHolderAccount, LIMIT_TIME_KEY, newTimeDue)
            .setAccountDetail(limitHolderAccount, LIMIT_AMOUNT_KEY, newLimitValue)
            .sign(queryAPI.getKeyPair())
            .build();
        TxStatus txStatus = sendWithLastResponseWaiting(
            queryAPI.getApi(),
            transaction
        ).getTxStatus();
        if (!txStatus.equals(TxStatus.COMMITTED)) {
          throw new IllegalStateException(
              "Could not update withdrawal limits. Got transaction status: " + txStatus.name()
                  + ". Hash: " + Utils.toHexHash(transaction)
          );
        }
        updateWithdrawalLimits(
            new XorWithdrawalLimitRemainder(
                new BigDecimal(newLimitValue),
                Long.parseLong(newTimeDue)
            )
        );
      } else {
        logger.error("Got corrupted withdrawal limits details, no value set");
      }
    }

    final BigDecimal withdrawalsAmount = processableObject.getSecond();
    if (withdrawalsAmount.compareTo(BigDecimal.ZERO) > 0) {
      logger.info("Got committed withdrawal limit transaction update");
      final XorWithdrawalLimitRemainder currentLimits = this.xorWithdrawalLimitRemainder.get();
      final BigDecimal remaining = getRelevantBrvsRemaining().subtract(withdrawalsAmount);
      final long timestampDue = currentLimits.getTimestampDue();
      final Transaction transaction = jp.co.soramitsu.iroha.java.Transaction
          .builder(queryAPI.getAccountId())
          .setAccountDetail(limitHolderAccount, LIMIT_TIME_KEY, String.valueOf(timestampDue))
          .setAccountDetail(limitHolderAccount, LIMIT_AMOUNT_KEY, remaining.toPlainString())
          .sign(queryAPI.getKeyPair())
          .build();
      TxStatus txStatus = sendWithLastResponseWaiting(
          queryAPI.getApi(),
          transaction
      ).getTxStatus();
      if (!txStatus.equals(TxStatus.COMMITTED)) {
        throw new IllegalStateException(
            "Could not update withdrawal limits. Got transaction status: " + txStatus.name()
                + ". Hash: " + Utils.toHexHash(transaction)
        );
      }
      updateWithdrawalLimits(
          new XorWithdrawalLimitRemainder(
              remaining,
              timestampDue
          )
      );
    }
  }

  private static class AccountDetailsData {

    private final String accountId;
    private final String key;
    private final String value;

    public AccountDetailsData(String accountId, String key, String value) {
      this.accountId = accountId;
      this.key = key;
      this.value = value;
    }
  }
}
