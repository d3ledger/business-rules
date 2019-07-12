/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules;

import static com.d3.commons.util.ThreadUtilKt.createPrettySingleThreadPool;

import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.SetAccountDetail;
import iroha.protocol.TransactionOuterClass.Transaction.Payload.ReducedPayload;
import iroha.validation.listener.BrvsIrohaChainListener;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.validators.Validator;
import java.util.Objects;
import java.util.Set;
import jp.co.soramitsu.iroha.java.QueryAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Class for listening for blocks and react on rules configuration updates
 */
public class RuleMonitor {

  private static final Logger logger = LoggerFactory.getLogger(RuleMonitor.class);

  private final Scheduler scheduler = Schedulers.from(createPrettySingleThreadPool(
      "rule-monitor", "chain-listener"
  ));
  private final QueryAPI queryAPI;
  private final BrvsIrohaChainListener irohaChainListener;
  private final String repositoryAccountId;
  private final String settingsAccountId;
  private final String setterAccountId;
  private final Validator validator;
  private volatile boolean isStarted = false;

  public RuleMonitor(QueryAPI queryAPI,
      BrvsIrohaChainListener irohaChainListener,
      String repositoryAccountId,
      String settingsAccountId, String setterAccountId,
      Validator validator) {
    this.queryAPI = queryAPI;
    Objects.requireNonNull(queryAPI, "QueryAPI must not be null");
    Objects.requireNonNull(irohaChainListener, "IrohaChainListener must not be null");
    if (StringUtils.isEmpty(repositoryAccountId)) {
      throw new IllegalArgumentException(
          "Repository account ID must not be neither null nor empty"
      );
    }
    if (StringUtils.isEmpty(settingsAccountId)) {
      throw new IllegalArgumentException("Settings account ID must not be neither null nor empty");
    }
    if (StringUtils.isEmpty(setterAccountId)) {
      throw new IllegalArgumentException("Setter account ID must not be neither null nor empty");
    }
    Objects.requireNonNull(validator, "ValidationServiceContext must not be null");

    this.irohaChainListener = irohaChainListener;
    this.repositoryAccountId = repositoryAccountId;
    this.settingsAccountId = settingsAccountId;
    this.setterAccountId = setterAccountId;
    this.validator = validator;
  }

  /**
   * Starts blocks processing
   */
  public synchronized void monitorUpdates() {
    if (!isStarted) {
      logger.info("Starting rules updates monitoring");
      irohaChainListener.getBlockStreaming().observeOn(scheduler).subscribe(block ->
          block.getBlockV1().getPayload().getTransactionsList().stream()
              .map(transaction -> transaction.getPayload().getReducedPayload())
              .filter(
                  reducedPayload -> reducedPayload.getCreatorAccountId().equals(setterAccountId)
              )
              .map(ReducedPayload::getCommandsList)
              .forEach(commands -> commands.stream()
                  .filter(Command::hasSetAccountDetail)
                  .map(Command::getSetAccountDetail)
                  .filter(
                      setAccountDetail -> setAccountDetail.getAccountId()
                          .equals(settingsAccountId)
                  )
                  .forEach(this::processUpdate)
              )
      );
      isStarted = true;
    }
  }

  /**
   * Performs actual update of a rules list
   *
   * @param detail Iroha command to extract data from
   */
  private void processUpdate(SetAccountDetail detail) {
    final String ruleName = detail.getKey();
    final boolean ruleValue = Boolean.parseBoolean(detail.getValue());
    final Set<String> rules = validator.getRuleNames();
    if (rules.contains(ruleName)) {
      if (ruleValue) {
        logger.warn("Rule [" + ruleName + "] has already been enabled");
      } else {
        validator.removeRule(ruleName);
        logger.info("Disabled rule [" + ruleName + "]");
      }
    } else {
      if (ruleValue) {
        try {
          validator.putRule(ruleName, parseRepositoryRule(ruleName));
          logger.info("Enabled rule [" + ruleName + "]");
        } catch (Exception e) {
          logger.error("Error during rule processing", e);
        }
      } else {
        logger.warn("Rule [" + ruleName + "] has already been disabled");
      }
    }
  }

  /**
   * Reads a rule from repository and instantiates it
   *
   * @param name Rule name to instantiate
   * @return {@link Rule} object
   */
  private Rule parseRepositoryRule(String name) {
    final String script = ValidationUtils.parser
        .parse(queryAPI.getAccountDetails(repositoryAccountId, setterAccountId, name))
        .getAsJsonObject()
        .get(setterAccountId)
        .getAsJsonObject()
        .get(name)
        .getAsString();
    return RuleParser.parse(script);
  }
}
