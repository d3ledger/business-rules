/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl.sora;

import static iroha.validation.rules.impl.billing.BillingRule.XOR_ASSET_ID;
import static iroha.validation.utils.ValidationUtils.sendWithLastResponseWaiting;

import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.transactions.core.provider.RegisteredUsersStorage;
import iroha.validation.transactions.filter.sora.XorTransfersTemporaryIgnoringFilter;
import iroha.validation.transactions.plugin.PluggableLogic;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.KeyPair;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.TransactionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * VAL token distribution logic processor
 */
public class ValDistributionPluggableLogic extends PluggableLogic<BigDecimal> {

  private static final Logger logger = LoggerFactory.getLogger(ValDistributionPluggableLogic.class);
  private static final String SORA_DOMAIN = "sora";
  public static final String VAL_ASSET_ID = "val#" + SORA_DOMAIN;
  private static final String VAL_AIRDROP_DESCRIPTION = "VAL airdrop";
  private static final int VAL_PRECISION = 18;
  private static final int PROPORTION_PRECISION = 64;
  private static final MathContext VAL_MATH_CONTEXT = new MathContext(
      Integer.MAX_VALUE,
      RoundingMode.DOWN
  );
  private final IrohaAPI irohaAPI;
  private final String brvsAccountId;
  private final KeyPair brvsKeypair;
  private final String infoSetterAccount;
  private final IrohaQueryHelper irohaQueryHelper;
  private final RegisteredUsersStorage registeredUsersStorage;
  private final BigDecimal totalProportionPool;
  private final XorTransfersTemporaryIgnoringFilter xorTransfersTemporaryIgnoringFilter;

  public ValDistributionPluggableLogic(
      QueryAPI queryAPI,
      String infoSetterAccount,
      IrohaQueryHelper irohaQueryHelper,
      RegisteredUsersStorage registeredUsersStorage,
      String totalProportionPool,
      XorTransfersTemporaryIgnoringFilter xorTransfersTemporaryIgnoringFilter) {
    Objects.requireNonNull(queryAPI, "Query API must not be null");
    if (StringUtils.isEmpty(infoSetterAccount)) {
      throw new IllegalArgumentException("Info setter account must not be neither null nor empty");
    }
    Objects.requireNonNull(irohaQueryHelper, "IrohaQueryHelper must not be null");
    Objects.requireNonNull(registeredUsersStorage, "RegisteredUsersStorage must not be null");
    if (StringUtils.isEmpty(totalProportionPool)) {
      throw new IllegalArgumentException("TotalProportionPool must not be neither null nor empty");
    }
    Objects.requireNonNull(
        xorTransfersTemporaryIgnoringFilter,
        "XorTransfersTemporaryIgnoringFilter must not be null"
    );

    this.irohaAPI = queryAPI.getApi();
    this.brvsAccountId = queryAPI.getAccountId();
    this.brvsKeypair = queryAPI.getKeyPair();
    this.infoSetterAccount = infoSetterAccount;
    this.irohaQueryHelper = irohaQueryHelper;
    this.registeredUsersStorage = registeredUsersStorage;
    this.totalProportionPool = new BigDecimal(totalProportionPool);
    this.xorTransfersTemporaryIgnoringFilter = xorTransfersTemporaryIgnoringFilter;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BigDecimal filterAndTransform(Block block) {
    return block.getBlockV1().getPayload().getTransactionsList()
        .stream()
        .filter(transaction ->
            transaction.getPayload().getReducedPayload().getCreatorAccountId()
                .equals(infoSetterAccount)
        )
        .flatMap(transaction ->
            transaction.getPayload().getReducedPayload().getCommandsList().stream()
        )
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .filter(transferAsset -> transferAsset.getSrcAccountId().equals(infoSetterAccount) &&
            transferAsset.getDestAccountId().equals(brvsAccountId) &&
            transferAsset.getAssetId().equals(VAL_ASSET_ID)
        )
        .map(TransferAsset::getAmount)
        .map(BigDecimal::new)
        .reduce(
            BigDecimal.ZERO,
            BigDecimal::add
        );
  }

  @Override
  protected void applyInternal(BigDecimal amountToDistribute) {
    if (amountToDistribute.signum() != 1) {
      return;
    }
    final BigDecimal brvsValBalance = new BigDecimal(
        irohaQueryHelper
            .getAccountAsset(brvsAccountId, VAL_ASSET_ID)
            .get()
    );
    if (brvsValBalance.compareTo(amountToDistribute) < 0) {
      logger.warn("BRVS balance is insufficient for the distribution");
      return;
    }
    xorTransfersTemporaryIgnoringFilter.enable();
    logger.info("Triggered VAL distribution of {} VALs", amountToDistribute.toPlainString());
    final Set<DistributionEntry> transactionsContext =
        registeredUsersStorage.process((userAccounts) ->
            StreamSupport.stream(userAccounts.spliterator(), false)
                .map(userAccount -> new DistributionEntry(
                        userAccount,
                        calculateAmountToDistributeFromTotal(
                            amountToDistribute,
                            getUserProportion(userAccount)
                        )
                    )
                )
                .filter(entry -> entry.amount.signum() == 1)
                .collect(Collectors.toList()));

    constructAndSendDistributions(transactionsContext, brvsValBalance);
  }

  private BigDecimal calculateAmountToDistributeFromTotal(
      BigDecimal total,
      BigDecimal proportion) {
    return total.multiply(proportion, VAL_MATH_CONTEXT)
        .setScale(VAL_PRECISION, RoundingMode.DOWN);
  }

  private BigDecimal getUserProportion(String userAccount) {
    return new BigDecimal(irohaQueryHelper.getAccountAsset(userAccount, XOR_ASSET_ID).get())
        .divide(totalProportionPool, PROPORTION_PRECISION, RoundingMode.DOWN);
  }

  private void constructAndSendDistributions(
      Set<DistributionEntry> distributionEntries,
      BigDecimal brvsValBalance) {
    final TransactionBuilder transactionBuilder = Transaction.builder(brvsAccountId);
    final AtomicReference<BigDecimal> toBurn = new AtomicReference<>(brvsValBalance);

    distributionEntries
        .forEach(entry -> {
              toBurn.set(toBurn.get().subtract(entry.amount));
              transactionBuilder
                  .transferAsset(
                      brvsAccountId,
                      entry.destinationAccountId,
                      VAL_ASSET_ID,
                      VAL_AIRDROP_DESCRIPTION,
                      entry.amount
                  );
            }
        );
    final BigDecimal toBurnValue = toBurn.get();
    if (toBurnValue.signum() == 1) {
      transactionBuilder.subtractAssetQuantity(
          VAL_ASSET_ID,
          toBurnValue
      );
    }

    final TransactionOuterClass.Transaction transaction = transactionBuilder
        .sign(brvsKeypair)
        .build();

    logger.debug("Constructed transaction: {}", transaction);

    final TxStatus txStatus = sendWithLastResponseWaiting(
        irohaAPI,
        transaction
    ).getTxStatus();
    xorTransfersTemporaryIgnoringFilter.disable();
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      throw new IllegalStateException(
          "Could not send VAL distribution. Got transaction status: " + txStatus.name()
      );
    }
    logger.info("Successfully sent VAL distribution");
  }

  public static class DistributionEntry {

    private final String destinationAccountId;
    private final BigDecimal amount;

    public DistributionEntry(String destinationAccountId, BigDecimal amount) {
      this.destinationAccountId = destinationAccountId;
      this.amount = amount;
    }
  }
}
