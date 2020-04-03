/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl;

import static iroha.validation.utils.ValidationUtils.advancedQueryAccountDetails;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.plugin.PluggableLogic;
import iroha.validation.utils.ValidationUtils;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
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
public class SoraDistributionPluggableLogic
    extends PluggableLogic<Transaction, Map<String, BigDecimal>> {

  private static final Logger logger = LoggerFactory
      .getLogger(SoraDistributionPluggableLogic.class);
  private static final String COMMA_SPACES_REGEX = ",\\s*";
  public static final String DISTRIBUTION_PROPORTIONS_KEY = "distribution";
  public static final String DISTRIBUTION_FINISHED_KEY = "distribution_finished";
  private static final String XOR_ASSET_ID = "xor#sora";
  private static final MathContext XOR_MATH_CONTEXT = new MathContext(18, RoundingMode.DOWN);
  private static final int TRANSACTION_SIZE = 9999;
  private static final String DESCRIPTION_FORMAT = "Distribution from %s";

  private final Set<String> projectAccounts;
  private final QueryAPI queryAPI;
  private final String brvsAccountId;
  private final KeyPair brvsKeypair;
  private final String infoSetterAccount;

  public SoraDistributionPluggableLogic(
      QueryAPI queryAPI,
      String projectAccounts,
      String infoSetterAccount) {
    Objects.requireNonNull(queryAPI, "Query API must not be null");
    if (StringUtils.isEmpty(projectAccounts)) {
      throw new IllegalArgumentException("Project accounts must not be neither null nor empty");
    }
    if (StringUtils.isEmpty(infoSetterAccount)) {
      throw new IllegalArgumentException("Info setter account must not be neither null nor empty");
    }

    this.queryAPI = queryAPI;
    this.brvsAccountId = queryAPI.getAccountId();
    this.brvsKeypair = queryAPI.getKeyPair();
    this.projectAccounts = new HashSet<>(Arrays.asList(projectAccounts.split(COMMA_SPACES_REGEX)));
    this.infoSetterAccount = infoSetterAccount;

    logger.info("Started distribution processor with project accounts: {}", this.projectAccounts);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<String, BigDecimal> filterAndTransform(Iterable<Transaction> sourceObjects) {
    // sums all the xor transfers per project owner
    return StreamSupport.stream(sourceObjects.spliterator(), false)
        .flatMap(
            transaction -> transaction.getPayload()
                .getReducedPayload()
                .getCommandsList()
                .stream()
        )
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .filter(command -> XOR_ASSET_ID.equals(command.getAssetId()) &&
            projectAccounts.contains(command.getSrcAccountId()))
        .collect(
            Collectors.groupingBy(
                TransferAsset::getSrcAccountId,
                Collectors.reducing(
                    BigDecimal.ZERO,
                    transfer -> new BigDecimal(transfer.getAmount()),
                    BigDecimal::add
                )
            )
        );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void applyInternal(Map<String, BigDecimal> processableObject) {
    processDistributions(processableObject);
  }

  /**
   * Processes committed project owners transfers and performs corresponding distributions if
   * needed
   *
   * @param transferAssetMap {@link Map} of project owner account id to aggregated volume of their
   * transfer within the block
   */
  private void processDistributions(Map<String, BigDecimal> transferAssetMap) {
    // list for batches to send after processing
    final List<Transaction> transactionList = new ArrayList<>();
    transferAssetMap.forEach((projectOwnerAccountId, transferAmount) -> {
      logger.info("Triggered distributions for {}", projectOwnerAccountId);
      final SoraDistributionFinished distributionFinished = queryDistributionsFinishedForAccount(
          projectOwnerAccountId
      );
      if (distributionFinished != null
          && distributionFinished.finished != null
          && distributionFinished.finished) {
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
      if (initialProportions == null
          || initialProportions.accountProportions == null
          || initialProportions.accountProportions.isEmpty()) {
        logger.warn(
            "No proportions have been set for project {}. Omitting.",
            projectOwnerAccountId
        );
        return;
      }
      // if brvs hasn't set values yet
      if (suppliesLeft == null || suppliesLeft.accountProportions == null
          || suppliesLeft.accountProportions.isEmpty()) {
        logger.warn("BRVS distribution state hasn't been set yet for {}", projectOwnerAccountId);
        suppliesLeft = constructInitialAmountMap(initialProportions);
      }
      final SoraDistributionProportions finalSuppliesLeft = suppliesLeft;
      // <String -> Amount> map for the project client accounts
      final Map<String, BigDecimal> toDistributeMap = initialProportions.accountProportions
          .entrySet()
          .stream()
          .collect(
              Collectors.toMap(
                  Entry::getKey,
                  entry -> calculateAmountForDistribution(
                      entry.getValue(),
                      transferAmount,
                      finalSuppliesLeft.accountProportions.get(entry.getKey())
                  )
              )
          );
      transactionList.addAll(
          constructTransactions(
              projectOwnerAccountId,
              transferAmount,
              finalSuppliesLeft,
              toDistributeMap
          )
      );
    });
    sendDistributions(transactionList);
  }

  private void sendDistributions(List<Transaction> distributionTransactions) {
    if (!distributionTransactions.isEmpty()) {
      final Iterable<Transaction> atomicBatch = Utils.createTxAtomicBatch(
          distributionTransactions,
          brvsKeypair
      );
      final IrohaAPI irohaAPI = queryAPI.getApi();
      irohaAPI.transactionListSync(atomicBatch);
      final byte[] byteHash = Utils.hash(atomicBatch.iterator().next());
      final TxStatus txStatus = ValidationUtils.subscriptionStrategy
          .subscribe(irohaAPI, byteHash)
          .blockingLast()
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
  }

  private List<Transaction> constructTransactions(
      String projectOwnerAccountId,
      BigDecimal transferAmount,
      SoraDistributionProportions supplies,
      Map<String, BigDecimal> toDistributeMap) {
    int commandCounter = 0;
    final List<Transaction> transactionList = new ArrayList<>();
    final SoraDistributionProportions afterDistribution = getSuppliesLeftAfterDistributions(
        supplies,
        transferAmount,
        toDistributeMap
    );
    final SoraDistributionFinished soraDistributionFinished = new SoraDistributionFinished(
        afterDistribution.totalSupply.signum() == 0
    );
    transactionList.add(
        constructDetailTransaction(
            projectOwnerAccountId,
            afterDistribution,
            soraDistributionFinished
        )
    );
    TransactionBuilder transactionBuilder = jp.co.soramitsu.iroha.java.Transaction
        .builder(brvsAccountId);
    // In case it is going to finish, add all amount left
    if (soraDistributionFinished.finished) {
      afterDistribution.accountProportions.forEach((account, amount) ->
          toDistributeMap.merge(account, amount, BigDecimal::add)
      );
    }
    for (Map.Entry<String, BigDecimal> entry : toDistributeMap.entrySet()) {
      final BigDecimal amount = entry.getValue();
      if (amount.signum() == 1) {
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
      }
    }
    if (commandCounter > 0) {
      transactionList.add(transactionBuilder.build().build());
    }
    logger.debug("Constrtucted project distribution transactions: count - {}",
        transactionList.size()
    );
    return transactionList;
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
        String.format(DESCRIPTION_FORMAT, projectOwnerAccountId),
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
      SoraDistributionFinished soraDistributionFinished) {
    return jp.co.soramitsu.iroha.java.Transaction.builder(brvsAccountId)
        .setAccountDetail(
            projectOwnerAccountId,
            DISTRIBUTION_PROPORTIONS_KEY,
            Utils.irohaEscape(
                ValidationUtils.gson.toJson(suppliesLeftAfterDistributions)
            )
        )
        .setAccountDetail(
            projectOwnerAccountId,
            DISTRIBUTION_FINISHED_KEY,
            Utils.irohaEscape(
                ValidationUtils.gson.toJson(soraDistributionFinished)
            )
        )
        .sign(brvsKeypair)
        .build();
  }

  private SoraDistributionProportions getSuppliesLeftAfterDistributions(
      SoraDistributionProportions supplies,
      BigDecimal transferAmount,
      Map<String, BigDecimal> toDistributeMap) {
    final Map<String, BigDecimal> accountProportions = supplies.accountProportions;
    final Map<String, BigDecimal> resultingSuppliesMap = accountProportions.entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                entry -> {
                  BigDecimal subtrahend = toDistributeMap.get(entry.getKey());
                  if (subtrahend == null) {
                    subtrahend = BigDecimal.ZERO;
                  }
                  return entry.getValue().subtract(subtrahend);
                }
            )
        );
    final BigDecimal supplyWithdrawn = supplies.totalSupply.subtract(transferAmount);
    final BigDecimal supplyLeft =
        supplyWithdrawn.signum() == -1 ? BigDecimal.ZERO : supplyWithdrawn;
    return new SoraDistributionProportions(
        resultingSuppliesMap,
        supplyLeft
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
                entry -> totalSupply.multiply(entry.getValue(), XOR_MATH_CONTEXT)
            )
        );

    return new SoraDistributionProportions(
        decimalMap,
        totalSupply
    );
  }

  private BigDecimal calculateAmountForDistribution(
      BigDecimal percentage,
      BigDecimal transferAmount,
      BigDecimal leftToDistribute) {
    final BigDecimal calculated = transferAmount.multiply(percentage, XOR_MATH_CONTEXT);
    if (leftToDistribute == null) {
      return calculated;
    }
    return calculated.min(leftToDistribute);
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

  public static class SoraDistributionProportions {

    // Account -> percentage from Sora
    // Account -> left in absolute measure from BRVS
    protected Map<String, BigDecimal> accountProportions;
    protected BigDecimal totalSupply;

    public SoraDistributionProportions(
        Map<String, BigDecimal> accountProportions,
        BigDecimal totalSupply) {
      this.accountProportions = accountProportions;
      this.totalSupply = totalSupply;
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
}
