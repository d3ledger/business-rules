/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl.sora;

import static iroha.validation.utils.ValidationUtils.advancedQueryAccountDetails;
import static iroha.validation.utils.ValidationUtils.gson;
import static iroha.validation.utils.ValidationUtils.trackHashWithLastResponseWaiting;

import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.protocol.TransactionOuterClass.Transaction.Payload.ReducedPayload;
import iroha.validation.rules.impl.billing.BillingInfo;
import iroha.validation.rules.impl.billing.BillingInfo.BillingTypeEnum;
import iroha.validation.rules.impl.billing.BillingRule;
import iroha.validation.transactions.plugin.PluggableLogic;
import iroha.validation.transactions.plugin.impl.sora.SoraDistributionPluggableLogic.SoraDistributionInputContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.TransactionBuilder;
import jp.co.soramitsu.iroha.java.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Sora distribution logic processor
 */
public class SoraDistributionPluggableLogic extends PluggableLogic<SoraDistributionInputContext> {

  private static final Logger logger = LoggerFactory
      .getLogger(SoraDistributionPluggableLogic.class);
  public static final String DISTRIBUTION_PROPORTIONS_KEY = "distribution";
  public static final String DISTRIBUTION_FINISHED_KEY = "distribution_finished";
  private static final String SORA_DOMAIN = "sora";
  private static final String XOR_ASSET_ID = "xor#" + SORA_DOMAIN;
  private static final int XOR_PRECISION = 18;
  private static final MathContext XOR_MATH_CONTEXT = new MathContext(
      Integer.MAX_VALUE,
      RoundingMode.DOWN
  );
  private static final int TRANSACTION_SIZE = 9999;
  private static final String DESCRIPTION_FORMAT = "Distribution from %s";
  private static final BigDecimal FEE_RATE = new BigDecimal("100");

  private final QueryAPI queryAPI;
  private final String brvsAccountId;
  private final KeyPair brvsKeypair;
  private final String infoSetterAccount;
  // for fee retrieval
  private final BillingRule billingRule;
  private final ProjectAccountProvider projectAccountProvider;
  private final IrohaQueryHelper irohaQueryHelper;

  public SoraDistributionPluggableLogic(
      QueryAPI queryAPI,
      String infoSetterAccount,
      BillingRule billingRule,
      ProjectAccountProvider projectAccountProvider,
      IrohaQueryHelper irohaQueryHelper) {
    Objects.requireNonNull(queryAPI, "Query API must not be null");
    if (StringUtils.isEmpty(infoSetterAccount)) {
      throw new IllegalArgumentException("Info setter account must not be neither null nor empty");
    }
    Objects.requireNonNull(billingRule, "Billing rule must not be null");
    Objects.requireNonNull(projectAccountProvider, "ProjectAccountProvider must not be null");
    Objects.requireNonNull(irohaQueryHelper, "IrohaQueryHelper must not be null");

    this.queryAPI = queryAPI;
    this.brvsAccountId = queryAPI.getAccountId();
    this.brvsKeypair = queryAPI.getKeyPair();
    this.infoSetterAccount = infoSetterAccount;
    this.billingRule = billingRule;
    this.projectAccountProvider = projectAccountProvider;
    this.irohaQueryHelper = irohaQueryHelper;
  }

  private <T> List<T> mergeLists(List<T> first, List<T> second) {
    final ArrayList<T> list = new ArrayList<>();
    list.addAll(first);
    list.addAll(second);
    return list;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SoraDistributionInputContext filterAndTransform(Iterable<Transaction> sourceObjects) {
    return filterAndTransformInternal(sourceObjects, true);
  }

  private SoraDistributionInputContext filterAndTransformInternal(
      Iterable<Transaction> sourceObjects,
      boolean checkAccounts) {
    // sums all the xor transfers and subtractions per project owner
    return new SoraDistributionInputContext(
        StreamSupport
            .stream(sourceObjects.spliterator(), false)
            .map(tx -> tx.getPayload().getReducedPayload())
            .filter(reducedPayload -> !checkAccounts ||
                projectAccountProvider.isProjectAccount(reducedPayload.getCreatorAccountId())
            )
            .collect(
                Collectors.groupingBy(
                    ReducedPayload::getCreatorAccountId,
                    Collectors.reducing(
                        BigDecimal.ZERO,
                        reducedPayload -> reducedPayload
                            .getCommandsList()
                            .stream()
                            .map(command -> {
                              if (command.hasSubtractAssetQuantity() &&
                                  XOR_ASSET_ID.equals(
                                      command.getSubtractAssetQuantity().getAssetId())
                              ) {
                                return new BigDecimal(
                                    command.getSubtractAssetQuantity().getAmount());
                              } else if (command.hasTransferAsset() &&
                                  XOR_ASSET_ID.equals(command.getTransferAsset().getAssetId())
                              ) {
                                return new BigDecimal(command.getTransferAsset().getAmount());
                              } else {
                                return BigDecimal.ZERO;
                              }
                            })
                            .reduce(BigDecimal::add)
                            .orElse(BigDecimal.ZERO),
                        BigDecimal::add
                    )
                )
            )
            .entrySet()
            .stream()
            // only > 0
            .filter(entry -> entry.getValue().signum() == 1)
            .collect(
                Collectors.toMap(
                    Entry::getKey,
                    Entry::getValue
                )
            ),
        StreamSupport
            .stream(sourceObjects.spliterator(), false)
            .map(tx -> tx.getPayload().getReducedPayload().getCreatedTime())
            .max(Comparator.naturalOrder())
            .orElse(System.currentTimeMillis())
    );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void applyInternal(SoraDistributionInputContext processableObject) {
    processDistributions(processableObject);
  }

  private BigDecimal getFeeSafely() {
    final BillingInfo info = billingRule
        .getBillingInfoFor(SORA_DOMAIN, XOR_ASSET_ID, BillingTypeEnum.TRANSFER);
    if (info == null) {
      return BigDecimal.ZERO;
    }
    return info.getFeeFraction();
  }

  /**
   * Processes committed project owners transfers and performs corresponding distributions if
   * needed
   *
   * @param soraDistributionInputContext {@link SoraDistributionInputContext} of project owner
   * account id to aggregated volume of their transfer within the block and the timestamp to use
   */
  private void processDistributions(SoraDistributionInputContext soraDistributionInputContext) {
    final Boolean isContextEmpty = Optional.ofNullable(soraDistributionInputContext)
        .map(context -> context.projectAmountMap)
        .map(Map::isEmpty)
        .orElse(true);
    if (!isContextEmpty) {
      final Map<String, BigDecimal> transferAssetMap = soraDistributionInputContext.projectAmountMap;
      // map for batches to send after processing
      final Map<String, List<Transaction>> transactionMap = new HashMap<>();
      final BigDecimal fee = getFeeSafely();
      transferAssetMap.forEach((projectOwnerAccountId, transferAmount) -> {
        logger.info("Triggered distributions for {}", projectOwnerAccountId);
        final SoraDistributionFinished distributionFinished = queryDistributionsFinishedForAccount(
            projectOwnerAccountId
        );
        final boolean isFinished = Optional.ofNullable(distributionFinished)
            .map(SoraDistributionFinished::getFinished)
            .orElse(false);
        if (isFinished) {
          logger.info("No need to perform any more distributions for {}", projectOwnerAccountId);
          return;
        }
        SoraDistributionProportions suppliesLeft = queryProportionsForAccount(
            projectOwnerAccountId,
            brvsAccountId
        );
        final SoraDistributionProportions initialProportions = queryProportionsForAccount(
            projectOwnerAccountId
        );
        final boolean isProportionsEmpty = Optional.ofNullable(initialProportions)
            .map(p -> p.accountProportions)
            .map(Map::isEmpty)
            .orElse(true);
        if (isProportionsEmpty) {
          logger.warn(
              "No proportions have been set for project {}. Omitting.",
              projectOwnerAccountId
          );
          return;
        }
        // if brvs hasn't set values yet
        final boolean isBrvsProportionsEmpty = Optional.ofNullable(suppliesLeft)
            .map(p -> p.accountProportions)
            .map(Map::isEmpty)
            .orElse(true);
        if (isBrvsProportionsEmpty) {
          logger.warn("BRVS distribution state hasn't been set yet for {}", projectOwnerAccountId);
          suppliesLeft = constructInitialAmountMap(initialProportions);
        }
        final SoraDistributionProportions finalSuppliesLeft = suppliesLeft;
        BigDecimal multipliedFee = multiplyWithRespect(fee, FEE_RATE, false);
        final Map<String, BigDecimal> feesByUsers = calculateFeesByUsers(
            multipliedFee,
            initialProportions.accountProportions
        );
        final BigDecimal transferAmountWithFeeExcluded = transferAmount.subtract(multipliedFee);
        // <String -> Amount> map for the project client accounts
        final Map<String, BigDecimal> toDistributeMap = initialProportions.accountProportions
            .entrySet()
            .stream()
            .collect(
                Collectors.toMap(
                    Entry::getKey,
                    entry -> calculateAmountForDistribution(
                        entry.getValue(),
                        transferAmountWithFeeExcluded,
                        finalSuppliesLeft.accountProportions.get(entry.getKey())
                    )
                )
            );

        transactionMap.merge(
            projectOwnerAccountId,
            constructTransactions(
                projectOwnerAccountId,
                transferAmount,
                finalSuppliesLeft,
                toDistributeMap,
                fee,
                feesByUsers,
                soraDistributionInputContext.timestamp
            ),
            this::mergeLists
        );
      });
      sendDistributions(transactionMap);
    }
  }

  private void sendDistributions(Map<String, List<Transaction>> distributionTransactions) {
    if (distributionTransactions != null && !distributionTransactions.isEmpty()) {
      distributionTransactions.forEach((projectAccount, transactions) -> {
        if (transactions != null && !transactions.isEmpty()) {
          final BigDecimal currentBalance = getBrvsXorBalance();
          final BigDecimal sumToSend = filterAndTransformInternal(transactions, false)
              .projectAmountMap
              .values()
              .stream()
              .reduce(BigDecimal::add)
              .orElse(BigDecimal.ZERO);
          if (sumToSend.compareTo(currentBalance) > 0) {
            logger.error(
                "BRVS has insufficient balance to perform the distribution for {}, needed - {}, present - {}",
                projectAccount,
                sumToSend.toPlainString(),
                currentBalance.toPlainString()
            );
            return;
          }
          final Iterable<Transaction> atomicBatch = Utils.createTxAtomicBatch(
              transactions,
              brvsKeypair
          );
          final IrohaAPI irohaAPI = queryAPI.getApi();
          // TODO XNET-96 persist state of distribution
          irohaAPI.transactionListSync(atomicBatch);
          final byte[] byteHash = Utils.hash(atomicBatch.iterator().next());
          final TxStatus txStatus = trackHashWithLastResponseWaiting(irohaAPI, byteHash)
              .getTxStatus();
          if (!txStatus.equals(TxStatus.COMMITTED)) {
            throw new IllegalStateException(
                "Could not perform distribution. Got transaction status: " + txStatus.name()
                    + ", hashes: " + StreamSupport.stream(atomicBatch.spliterator(), false)
                    .map(Utils::toHexHash).collect(Collectors.toList())
            );
          }
          logger.info("Successfully committed distribution");
        }
      });
    }
  }

  private List<Transaction> constructTransactions(
      String projectOwnerAccountId,
      BigDecimal transferAmount,
      SoraDistributionProportions supplies,
      Map<String, BigDecimal> toDistributeMap,
      BigDecimal fee,
      Map<String, BigDecimal> feesByUsers,
      long creationTime) {
    int commandCounter = 0;
    final List<Transaction> transactionList = new ArrayList<>();
    final SoraDistributionProportions afterDistribution = getSuppliesLeftAfterDistributions(
        supplies,
        transferAmount,
        toDistributeMap,
        feesByUsers
    );
    final SoraDistributionFinished soraDistributionFinished = new SoraDistributionFinished(
        afterDistribution.totalSupply.signum() == 0 ||
            afterDistribution.accountProportions.values().stream().allMatch(
                amount -> amount.signum() == 0
            )
    );
    TransactionBuilder transactionBuilder = jp.co.soramitsu.iroha.java.Transaction
        .builder(brvsAccountId, creationTime);
    // In case it is going to finish, add all amounts left
    if (soraDistributionFinished.finished) {
      afterDistribution.accountProportions.forEach((account, amount) ->
          toDistributeMap.merge(account, amount, BigDecimal::add)
      );
    }
    boolean anyDistributions = false;
    for (Map.Entry<String, BigDecimal> entry : toDistributeMap.entrySet()) {
      final BigDecimal amount = entry.getValue();
      if (amount.signum() == 1) {
        afterDistribution.rewardToDistribute = afterDistribution.rewardToDistribute
            .subtract(amount);
        appendDistributionCommand(
            projectOwnerAccountId,
            entry.getKey(),
            amount,
            transactionBuilder
        );
        commandCounter++;
        if (commandCounter == TRANSACTION_SIZE) {
          transactionList.add(transactionBuilder.build().build());
          transactionBuilder = jp.co.soramitsu.iroha.java.Transaction.builder(brvsAccountId);
          commandCounter = 0;
        }
        anyDistributions = true;
      }
    }
    if (commandCounter > 0) {
      transactionList.add(transactionBuilder.build().build());
    }
    if (anyDistributions) {
      transactionList.add(
          constructFeeTransaction(
              fee,
              creationTime
          )
      );
      afterDistribution.rewardToDistribute = afterDistribution.rewardToDistribute.subtract(fee);
    }
    // In case it is going to finish, burn remains
    if (soraDistributionFinished.finished && afterDistribution.rewardToDistribute.signum() == 1) {
      final Transaction remainsTransaction = constructBurnRemainsTransaction(
          afterDistribution.rewardToDistribute,
          creationTime
      );
      if (remainsTransaction != null) {
        transactionList.add(remainsTransaction);
      }
      afterDistribution.rewardToDistribute = BigDecimal.ZERO;
    }
    transactionList.add(
        constructDetailTransaction(
            projectOwnerAccountId,
            afterDistribution,
            soraDistributionFinished,
            creationTime
        )
    );
    logger.debug("Constructed project distribution transactions: count - {}",
        transactionList.size()
    );
    return transactionList;
  }

  private BigDecimal getBrvsXorBalance() {
    return new BigDecimal(irohaQueryHelper.getAccountAsset(brvsAccountId, XOR_ASSET_ID).get());
  }

  private void appendDistributionCommand(
      String projectOwnerAccountId,
      String clientAccountId,
      BigDecimal amount,
      TransactionBuilder transactionBuilder) {
    transactionBuilder.transferAsset(
        brvsAccountId,
        clientAccountId,
        XOR_ASSET_ID,
        String.format(
            DESCRIPTION_FORMAT,
            projectAccountProvider.getProjectDescription(projectOwnerAccountId)
        ),
        amount
    );
    logger.debug("Appended distribution command: project - {}, to - {}, amount - {}",
        projectOwnerAccountId,
        clientAccountId,
        amount.toPlainString()
    );
  }

  private Transaction constructDetailTransaction(
      String projectOwnerAccountId,
      SoraDistributionProportions suppliesLeftAfterDistributions,
      SoraDistributionFinished soraDistributionFinished,
      long creationTime) {
    return jp.co.soramitsu.iroha.java.Transaction.builder(brvsAccountId, creationTime)
        .setAccountDetail(
            projectOwnerAccountId,
            DISTRIBUTION_PROPORTIONS_KEY,
            Utils.irohaEscape(
                gson.toJson(suppliesLeftAfterDistributions)
            )
        )
        .setAccountDetail(
            projectOwnerAccountId,
            DISTRIBUTION_FINISHED_KEY,
            Utils.irohaEscape(
                gson.toJson(soraDistributionFinished)
            )
        )
        .sign(brvsKeypair)
        .build();
  }

  private Transaction constructFeeTransaction(BigDecimal fee, long creationTime) {
    if (fee.signum() < 1) {
      logger.warn("Got negative value for subtraction");
      return null;
    }
    return jp.co.soramitsu.iroha.java.Transaction.builder(brvsAccountId, creationTime)
        .subtractAssetQuantity(
            XOR_ASSET_ID,
            fee
        )
        .sign(brvsKeypair)
        .build();
  }

  private Transaction constructBurnRemainsTransaction(BigDecimal amount, long creationTime) {
    logger.info("Going to burn remaining distribution balance {}", amount.toPlainString());
    // for now is the same
    return constructFeeTransaction(amount, creationTime);
  }

  private SoraDistributionProportions getSuppliesLeftAfterDistributions(
      SoraDistributionProportions supplies,
      BigDecimal transferAmount,
      Map<String, BigDecimal> toDistributeMap,
      Map<String, BigDecimal> feesByUsers) {
    final BigDecimal totalSupply = supplies.totalSupply;
    final Map<String, BigDecimal> resultingSuppliesMap = supplies.accountProportions.entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                entry -> {
                  final String userId = entry.getKey();
                  final BigDecimal subtrahend = Optional.ofNullable(toDistributeMap.get(userId))
                      .orElse(BigDecimal.ZERO);
                  BigDecimal remainingForUser = entry.getValue()
                      .subtract(subtrahend)
                      .subtract(feesByUsers.get(userId));
                  if (remainingForUser.signum() == -1) {
                    toDistributeMap.put(userId, subtrahend.add(remainingForUser));
                    remainingForUser = BigDecimal.ZERO;
                  }
                  return remainingForUser;
                }
            )
        );
    final BigDecimal supplyWithdrawn = totalSupply.subtract(transferAmount);
    final BigDecimal supplyLeft =
        supplyWithdrawn.signum() == -1 ? BigDecimal.ZERO : supplyWithdrawn;
    return new SoraDistributionProportions(
        resultingSuppliesMap,
        supplyLeft,
        supplies.rewardToDistribute
    );
  }

  /**
   * Creates an instance with initial amount limits to be distributed
   *
   * @param initialProportion initial {@link SoraDistributionProportions} set by Sora
   * @return ready to be used {@link SoraDistributionProportions} as the first calculation
   */
  private SoraDistributionProportions constructInitialAmountMap(
      SoraDistributionProportions initialProportion) {
    final BigDecimal totalSupply = initialProportion.totalSupply;
    final Map<String, BigDecimal> decimalMap = initialProportion.accountProportions.entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                entry -> multiplyWithRespect(totalSupply, entry.getValue(), true)
            )
        );

    return new SoraDistributionProportions(
        decimalMap,
        totalSupply,
        initialProportion.rewardToDistribute
    );
  }

  private BigDecimal calculateAmountForDistribution(
      BigDecimal percentage,
      BigDecimal transferAmount,
      BigDecimal leftToDistribute) {
    final BigDecimal calculated = multiplyWithRespect(
        transferAmount,
        percentage,
        true
    );
    return Optional.ofNullable(leftToDistribute).map(calculated::min).orElse(calculated);
  }

  private Map<String, BigDecimal> calculateFeesByUsers(
      BigDecimal fee,
      Map<String, BigDecimal> proportions) {
    return proportions.entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                entry -> multiplyWithRespect(fee, entry.getValue(), false)
            )
        );
  }

  private SoraDistributionProportions queryProportionsForAccount(String accountId) {
    return queryProportionsForAccount(accountId, infoSetterAccount);
  }

  private SoraDistributionProportions queryProportionsForAccount(
      String accountId,
      String setterAccountId) {
    return advancedQueryAccountDetails(
        queryAPI,
        accountId,
        setterAccountId,
        DISTRIBUTION_PROPORTIONS_KEY,
        SoraDistributionProportions.class
    );
  }

  private SoraDistributionFinished queryDistributionsFinishedForAccount(String accountId) {
    return advancedQueryAccountDetails(
        queryAPI,
        accountId,
        brvsAccountId,
        DISTRIBUTION_FINISHED_KEY,
        SoraDistributionFinished.class
    );
  }

  private BigDecimal multiplyWithRespect(
      BigDecimal value,
      BigDecimal multiplicand,
      boolean roundDown) {
    return value.multiply(multiplicand, XOR_MATH_CONTEXT)
        .setScale(XOR_PRECISION, roundDown ? RoundingMode.DOWN : RoundingMode.UP);
  }

  public static class SoraDistributionProportions {

    // Account -> percentage from Sora
    // Account -> left in absolute measure from BRVS
    protected Map<String, BigDecimal> accountProportions;
    protected BigDecimal totalSupply;
    protected BigDecimal rewardToDistribute;

    public SoraDistributionProportions(
        Map<String, BigDecimal> accountProportions,
        BigDecimal totalSupply,
        BigDecimal rewardToDistribute) {
      this.accountProportions = accountProportions;
      this.totalSupply = totalSupply;
      this.rewardToDistribute = rewardToDistribute;
    }

    public BigDecimal getRewardToDistribute() {
      return rewardToDistribute;
    }
  }

  public static class SoraDistributionFinished {

    protected Boolean finished;

    public SoraDistributionFinished(Boolean finished) {
      this.finished = finished;
    }

    public Boolean getFinished() {
      return finished;
    }
  }

  public static class SoraDistributionInputContext {

    private Map<String, BigDecimal> projectAmountMap;

    private long timestamp;

    public SoraDistributionInputContext(
        Map<String, BigDecimal> projectAmountMap,
        long timestamp) {
      this.projectAmountMap = projectAmountMap;
      this.timestamp = timestamp;
    }
  }
}
