/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.whitelist;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.SetAccountDetail;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A rule that is triggered on a client whitelist update. Checks the difference in new and old
 * whitelist and updates BRVS records with validation period.
 */
public class UpdateWhitelistRule implements Rule {

  private static final Logger logger = LoggerFactory.getLogger(UpdateWhitelistRule.class);

  private final QueryAPI queryAPI;

  /**
   * When new address will be valid
   */
  private final long validationPeriod;

  public UpdateWhitelistRule(QueryAPI queryAPI, long validationPeriod) {
    Objects.requireNonNull(queryAPI, "Query API must not be null");
    this.queryAPI = queryAPI;

    this.validationPeriod = validationPeriod;
  }

  /**
   * Updates BRVS whitelist according to *client whitelist* - if client added an address -> add the
   * address to BRVS whitelist with new validation time - if client removed an address -> remove it
   * from BRVS whitelist
   *
   * @param transaction Iroha proto transaction
   * @return {@link ValidationResult}
   */
  @Override
  public ValidationResult isSatisfiedBy(TransactionOuterClass.Transaction transaction) {
    String clientId = transaction.getPayload().getReducedPayload().getCreatorAccountId();
    long createdTime = transaction.getPayload().getReducedPayload().getCreatedTime();

    return checkDetails(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasSetAccountDetail)
        .map(Command::getSetAccountDetail)
        .filter(cmd -> cmd.getAccountId().equals(clientId)
            && (cmd.getKey().equals(WhitelistUtils.ETH_WHITELIST_KEY)
            || cmd.getKey().equals(WhitelistUtils.BTC_WHITELIST_KEY)))
        .collect(Collectors.toList()), clientId, createdTime);
  }

  private ValidationResult checkDetails(List<SetAccountDetail> details,
      String clientId,
      long createdTime) {

    for (SetAccountDetail detail : details) {
      try {
        String whitelistKey = detail.getKey();
        List<String> clientWhitelist = WhitelistUtils.deserializeClientWhitelist(detail.getValue());
        logger.info("Client " + clientId + " changed whitelist " + whitelistKey + " to "
            + clientWhitelist);

        // get old whitelist that was set by BRVS as Pairs(address -> validation_time)
        Map<String, Long> oldWhitelistValidated = WhitelistUtils.getBRVSWhitelist(
            queryAPI,
            clientId,
            whitelistKey
        );

        // When whitelist is validated
        long validationTime = System.currentTimeMillis() / 1000 + validationPeriod;
        logger.info("ValidationTime: " + validationTime);

        // Prepare new whitelist
        // remove old addresses that user has removed
        Map<String, Long> newWhitelistValidated = oldWhitelistValidated.entrySet().stream()
            .filter(address -> clientWhitelist.contains(address.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // add new from user whitelist with new validationTime
        newWhitelistValidated.putAll(
            clientWhitelist.stream()
                .filter(address -> !oldWhitelistValidated.containsKey(address))
                .collect(Collectors.toMap(a -> a, t -> validationTime))
        );

        if (newWhitelistValidated.equals(oldWhitelistValidated)) {
          logger.info("No changes in whitelist, nothing to update");
        } else {
          String jsonNewBrvsWhitelist = WhitelistUtils
              .serializeBRVSWhitelist(newWhitelistValidated);
          logger.info("Send whitelist to Iroha: " + jsonNewBrvsWhitelist);

          queryAPI.getApi().transactionSync(
              Transaction.builder(queryAPI.getAccountId())
                  .setCreatedTime(createdTime)
                  .setAccountDetail(clientId, whitelistKey,
                      WhitelistUtils.irohaEscape(jsonNewBrvsWhitelist))
                  .sign(queryAPI.getKeyPair())
                  .build()
          );
        }
      } catch (Exception e) {
        logger.error("Error while updating whitelist ", e);
        return ValidationResult.REJECTED("Error while updating whitelist. " + e.getMessage());
      }
    }
    return ValidationResult.VALIDATED;
  }
}
