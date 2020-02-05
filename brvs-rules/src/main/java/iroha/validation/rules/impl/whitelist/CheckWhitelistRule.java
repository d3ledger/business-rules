/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.whitelist;

import com.google.common.base.Strings;
import iroha.protocol.Commands;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.QueryAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rule that checks whitelisted addresses on withdrawal.
 */
public class CheckWhitelistRule implements Rule {

  private static final Logger logger = LoggerFactory.getLogger(UpdateWhitelistRule.class);
  private static final String WITHDRAWAL_FEE_DESCRIPTION = "withdrawal fee";

  private final QueryAPI queryAPI;
  private final String withdrawalAccount;
  private final List<String> exceptionAssets;

  public CheckWhitelistRule(QueryAPI queryAPI, String withdrawalAccount, String exceptionAssets) {
    this(queryAPI, withdrawalAccount,
        Arrays.stream(exceptionAssets.split(",")).collect(Collectors.toList()));
  }

  public CheckWhitelistRule(QueryAPI queryAPI, String withdrawalAccount) {
    this(queryAPI, withdrawalAccount, new ArrayList<>());
  }

  public CheckWhitelistRule(QueryAPI queryAPI, String withdrawalAccount,
      List<String> exceptionAssets) {
    if (Strings.isNullOrEmpty(withdrawalAccount)) {
      throw new IllegalArgumentException(
          "Withdrawal Account ID must not be neither null nor empty");
    }
    this.withdrawalAccount = withdrawalAccount;

    Objects.requireNonNull(queryAPI, "Query API must not be null");
    this.queryAPI = queryAPI;
    Objects.requireNonNull(exceptionAssets, "Exception assets must not be null");
    this.exceptionAssets = exceptionAssets;
  }

  @Override
  public ValidationResult isSatisfiedBy(TransactionOuterClass.Transaction transaction) {
    logger.debug("Apply CheckWhitelistRule");

    return checkTransfers(
        transaction
            .getPayload()
            .getReducedPayload()
            .getCommandsList()
            .stream()
            .filter(Commands.Command::hasTransferAsset)
            .map(Commands.Command::getTransferAsset)
            .filter(transfer -> transfer.getDestAccountId().equals(withdrawalAccount))
            .collect(Collectors.toList())
    );
  }

  private ValidationResult checkTransfers(List<TransferAsset> transfers) {
    for (TransferAsset transfer : transfers) {
      try {
        String asset = transfer.getAssetId();
        String address = transfer.getDescription();
        if (exceptionAssets.contains(asset) || address.equals(WITHDRAWAL_FEE_DESCRIPTION)) {
          continue;
        }
        String clientId = transfer.getSrcAccountId();
        String assetDomain = WhitelistUtils.getAssetDomain(asset);

        String whitelistKey = WhitelistUtils.assetToWhitelistKey.get(assetDomain);

        // get old whitelist that was set by BRVS as Pairs(address -> validation_time)
        Map<String, Long> whitelistValidated = WhitelistUtils.getBRVSWhitelist(
            queryAPI,
            clientId,
            whitelistKey
        );

        long now = System.currentTimeMillis() / 1000;

        if (!whitelistValidated.containsKey(address)) {
          logger.info("Not allowed. " + address + " not in whitelist.");
          return ValidationResult.REJECTED(
              "Transfer not allowed. " + address + " not in whitelist."
          );
        }

        if (whitelistValidated.get(address) > now) {
          logger.info("Not allowed. Address " + address + " will be validated after "
              + whitelistValidated.get(address) + " now " + now);
          return ValidationResult.REJECTED(
              "Transfer not allowed. Address " + address + " will be validated after "
                  + whitelistValidated.get(address) + " now " + now
          );
        }
      } catch (Exception e) {
        logger.error("Error on parsing transfer", e);
        return ValidationResult.REJECTED("Error on parsing transfer: " + e.getMessage());
      }
    }
    return ValidationResult.VALIDATED;
  }
}
