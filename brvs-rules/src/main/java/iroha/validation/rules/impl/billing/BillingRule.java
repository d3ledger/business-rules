/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.billing;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.Runnables;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.SubtractAssetQuantity;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.billing.BillingInfo.BillingTypeEnum;
import iroha.validation.rules.impl.billing.BillingInfo.FeeTypeEnum;
import iroha.validation.verdict.ValidationResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

public class BillingRule implements Rule {

  private static final Logger logger = LoggerFactory.getLogger(BillingRule.class);

  public static final String ASSET_ID = "xor#sora";
  private static final String SEPARATOR = ",";
  private static final String QUEUE_NAME = "brvs_billing_updates";
  private static final String BILLING_ERROR_MESSAGE = "Couldn't request primary billing information.";
  private static final String BILLING_PRECISION_ERROR_MESSAGE = "Couldn't request asset precision.";
  private static final String BILLING_PRECISION_JSON_FIELD = "itIs";
  private static final String GET_BILLING_PATH = "/cache/get/billing";
  private static final String PRECISION_PATH = "/iroha/asset/precision/";
  private static final BigDecimal INCORRECT_FEE_VALUE = new BigDecimal(Integer.MIN_VALUE);
  private static final Map<String, Integer> assetPrecisionMap = new ConcurrentHashMap<>();
  private static final JsonParser jsonParser = new JsonParser();
  private static final Gson gson = new Gson();

  private boolean isRunning;
  private final String getBillingBaseURL;
  private final String rmqHost;
  private final int rmqPort;
  private final String rmqExchange;
  private final String rmqRoutingKey;
  private final String ethWithdrawalAccount;
  private final String btcWithdrawalAccount;
  private final String exchangerAccount;
  private final Set<String> userDomains;
  private final Set<String> depositAccounts;
  private final Set<BillingInfo> cache = ConcurrentHashMap.newKeySet();

  public BillingRule(String getBillingBaseURL,
      String rmqHost,
      int rmqPort,
      String rmqExchange,
      String rmqRoutingKey,
      String userDomains,
      String depositAccounts,
      String ethWithdrawalAccount,
      String btcWithdrawalAccount,
      String exchangerAccount) throws MalformedURLException {

    if (Strings.isNullOrEmpty(getBillingBaseURL)) {
      throw new IllegalArgumentException("Billing URL must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(rmqHost)) {
      throw new IllegalArgumentException("RMQ host must not be neither null nor empty");
    }
    if (rmqPort < 1 || rmqPort > 65535) {
      throw new IllegalArgumentException("RMQ port must be valid");
    }
    if (Strings.isNullOrEmpty(rmqExchange)) {
      throw new IllegalArgumentException("RMQ exchange must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(rmqRoutingKey)) {
      throw new IllegalArgumentException("RMQ routing key must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(userDomains)) {
      throw new IllegalArgumentException("User domains key must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(depositAccounts)) {
      throw new IllegalArgumentException("Deposit accounts key must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(ethWithdrawalAccount)) {
      throw new IllegalArgumentException(
          "ETH Withdrawal account must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(btcWithdrawalAccount)) {
      throw new IllegalArgumentException(
          "BTC Withdrawal account must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(exchangerAccount)) {
      throw new IllegalArgumentException(
          "Exchanger account must not be neither null nor empty");
    }

    this.getBillingBaseURL = getBillingBaseURL;
    this.rmqHost = rmqHost;
    this.rmqPort = rmqPort;
    this.rmqExchange = rmqExchange;
    this.rmqRoutingKey = rmqRoutingKey;
    this.userDomains = new HashSet<>(Arrays.asList(userDomains.split(SEPARATOR)));
    this.depositAccounts = new HashSet<>(Arrays.asList(depositAccounts.split(SEPARATOR)));
    this.ethWithdrawalAccount = ethWithdrawalAccount;
    this.btcWithdrawalAccount = btcWithdrawalAccount;
    this.exchangerAccount = exchangerAccount;
    runCacheUpdater();
  }

  protected void runCacheUpdater() throws MalformedURLException {
    if (isRunning) {
      logger.warn("Cache updater is already running");
      return;
    }
    isRunning = true;
    readBillingOnStartup();
    getMqUpdatesObservable()
        .observeOn(Schedulers.from(Executors.newSingleThreadExecutor()))
        .subscribe(update -> {
              logger.info("Got billing data update from MQ: " + update.toString());
              final BillingInfo currentBillingInfo = cache
                  .stream()
                  // equality check does not check the date and fraction
                  .filter(entry -> entry.equals(update))
                  .findAny()
                  .orElse(null);
              if (currentBillingInfo == null
                  || currentBillingInfo.getUpdated() < update.getUpdated()) {
                cache.remove(update);
                cache.add(update);
                logger.info("Updated billing data");
              } else {
                logger.info("Billing data is not relevant, won't update");
              }
            }
        );
    logger.info("Billing cache updater has been started");
  }

  private void readBillingOnStartup() throws MalformedURLException {
    final JsonObject root = jsonParser
        .parse(
            executeGetRequest(
                new URL(getBillingBaseURL + GET_BILLING_PATH),
                BILLING_ERROR_MESSAGE)
        )
        .getAsJsonObject();
    logger.info("Got billing data response from HTTP server: " + root);
    for (BillingTypeEnum billingType : BillingTypeEnum.values()) {
      final String label = billingType.label;
      cache.addAll(
          BillingInfo.parseBillingHttpDto(
              label,
              gson.fromJson(
                  root.getAsJsonObject(label),
                  new TypeToken<HashMap<String, HashMap<String, JsonObject>>>() {
                  }.getType()
              )
          )
      );
    }
  }

  private String executeGetRequest(URL url, String onRequestError) {
    HttpURLConnection urlConnection;
    try {
      urlConnection = (HttpURLConnection) url.openConnection();
      final int responseCode = urlConnection.getResponseCode();
      if (responseCode != 200) {
        throw new BillingRuleException(
            onRequestError + " Response code is " + responseCode
        );
      }
    } catch (IOException e) {
      throw new BillingRuleException("Error opening connection occurred", e);
    }

    try (BufferedReader in = new BufferedReader(
        new InputStreamReader(urlConnection.getInputStream()))) {
      String inputLine;
      StringBuilder response = new StringBuilder();
      while ((inputLine = in.readLine()) != null) {
        response.append(inputLine);
      }
      return response.toString();
    } catch (Exception e) {
      throw new BillingRuleException("Couldn't parse response", e);
    }
  }

  private String getRawAssetPrecisionResponse(String assetId) {
    try {
      return jsonParser
          .parse(
              executeGetRequest(
                  new URL(
                      getBillingBaseURL + PRECISION_PATH
                          + assetId.replace("#", "%23")
                  ),
                  BILLING_PRECISION_ERROR_MESSAGE
              )
          )
          .getAsJsonObject()
          .get(BILLING_PRECISION_JSON_FIELD)
          .getAsString();
    } catch (MalformedURLException e) {
      throw new BillingRuleException(BILLING_PRECISION_ERROR_MESSAGE, e);
    }
  }

  private Observable<BillingInfo> getMqUpdatesObservable() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rmqHost);
    factory.setPort(rmqPort);
    final Connection connection;
    try {
      connection = factory.newConnection();
    } catch (TimeoutException | IOException e) {
      throw new IllegalStateException("Cannot acquire MQ connection", e);
    }

    PublishSubject<Delivery> source = PublishSubject.create();
    DeliverCallback deliverCallback = (consumerTag, delivery) -> source.onNext(delivery);
    CancelCallback cancelCallback = consumerTag -> Runnables.doNothing().run();
    // start consumer
    try {
      Channel channel = connection.createChannel();
      channel.exchangeDeclare(rmqExchange, BuiltinExchangeType.TOPIC, true);
      String queue = channel.queueDeclare(QUEUE_NAME, true, false, false, null).getQueue();
      channel.queueBind(queue, rmqExchange, rmqRoutingKey);
      channel.basicConsume(queue, true, deliverCallback, cancelCallback);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot consume billing update", e);
    }

    return source.map(delivery -> BillingInfo
        .parseBillingMqDto(jsonParser.parse(new String(delivery.getBody())).getAsJsonObject())
    );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult isSatisfiedBy(Transaction transaction) {
    final List<Command> commandsList = transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList();
    final List<TransferAsset> transfers = commandsList
        .stream()
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .collect(Collectors.toList());
    final List<SubtractAssetQuantity> feesAsBurns = commandsList
        .stream()
        .filter(Command::hasSubtractAssetQuantity)
        .map(Command::getSubtractAssetQuantity)
        .collect(Collectors.toList());

    if (CollectionUtils.isEmpty(transfers)) {
      if (!CollectionUtils.isEmpty(feesAsBurns)) {
        return ValidationResult.REJECTED(
            "There are more fee operations supplied than needed:\nSubtraction fees: " + feesAsBurns
        );
      }
      return ValidationResult.VALIDATED;
    }
    return processFeeValidation(transfers, feesAsBurns);
  }

  private ValidationResult processFeeValidation(
      List<TransferAsset> transfers,
      List<SubtractAssetQuantity> feesAsBurns) {
    for (TransferAsset transferAsset : transfers) {
      final BillingTypeEnum originalType = getBillingType(transferAsset);
      if (originalType != null) {
        final BillingInfo billingInfo = getBillingInfoFor(
            BillingInfo.getDomain(transferAsset.getSrcAccountId()),
            // enforce xor for any fee
            ASSET_ID,
            originalType
        );
        // Not billable operation
        if (billingInfo == null) {
          continue;
        }

        final boolean isFeeFound = findAndRemoveFee(transferAsset, feesAsBurns, billingInfo);
        // If operation is billable but there is no corresponding fee attached
        if (!isFeeFound) {
          logger.error("There is no correct fee for:\n"
              + transferAsset + "\nFees:\n" + feesAsBurns
          );
          return ValidationResult.REJECTED("There is no fee for:\n" + transferAsset);
        }
      }
    }
    if (!CollectionUtils.isEmpty(feesAsBurns)) {
      return ValidationResult.REJECTED(
          "There are more fee operations left than needed after evaluation:\nSubtraction fees: "
              + feesAsBurns
      );
    }
    return ValidationResult.VALIDATED;
  }

  private boolean findAndRemoveFee(TransferAsset transfer,
      List<SubtractAssetQuantity> burnableFees,
      BillingInfo billingInfo) {

    final BigDecimal amount = new BigDecimal(transfer.getAmount());
    final BigDecimal relevantFeeAmount = calculateRelevantFeeAmount(amount, billingInfo);

    for (SubtractAssetQuantity fee : burnableFees) {
      if (fee.getAssetId().equals(ASSET_ID)
          && new BigDecimal(fee.getAmount())
          .compareTo(relevantFeeAmount) == 0) {
        // To prevent case when there are two identical operations and only one fee
        burnableFees.remove(fee);
        return true;
      }
    }
    logger.warn(
        "Corresponding fee is not found for the transfer. Must be: "
            + relevantFeeAmount + " "
            + ASSET_ID
    );
    return false;
  }

  private BigDecimal calculateRelevantFeeAmount(BigDecimal amount, BillingInfo billingInfo) {
    final FeeTypeEnum feeType = billingInfo.getFeeType();
    switch (feeType) {
      case FIXED: {
        return billingInfo.getFeeFraction();
      }
      case FRACTION: {
        final int assetPrecision = getAssetPrecision(billingInfo.getAsset());
        return amount.multiply(billingInfo.getFeeFraction())
            .setScale(assetPrecision, RoundingMode.UP);
      }
      default: {
        logger.error("Unknown fee type: " + feeType);
        return INCORRECT_FEE_VALUE;
      }
    }
  }

  private int getAssetPrecision(String assetId) {
    return assetPrecisionMap.computeIfAbsent(
        assetId,
        newPrecision -> Integer.valueOf(getRawAssetPrecisionResponse(assetId))
    );
  }

  private BillingTypeEnum getBillingType(TransferAsset transfer) {
    final String srcAccountId = transfer.getSrcAccountId();
    final String destAccountId = transfer.getDestAccountId();
    final String srcDomain = BillingInfo.getDomain(srcAccountId);
    final String destDomain = BillingInfo.getDomain(destAccountId);

    if (depositAccounts.contains(srcAccountId)
        && userDomains.contains(destDomain)) {
      // TODO Not yet decided for sure
      return BillingTypeEnum.ACCOUNT_CREATION;
    }
    if ((destAccountId.equals(ethWithdrawalAccount)) || (destAccountId.equals(btcWithdrawalAccount))
        && userDomains.contains(srcDomain)) {
      return BillingTypeEnum.WITHDRAWAL;
    }
    if (userDomains.contains(srcDomain)) {
      if (userDomains.contains(destDomain)) {
        return BillingTypeEnum.TRANSFER;
      }
      if (destAccountId.equals(exchangerAccount)) {
        return BillingTypeEnum.EXCHANGE;
      }
    }
    return null;
  }

  public BillingInfo getBillingInfoFor(String domain, String asset, BillingTypeEnum originalType) {
    return cache
        .stream()
        .filter(entry -> entry.getDomain().equals(domain)
            && entry.getBillingType().equals(originalType)
            && entry.getAsset().equals(asset)
            && entry.getFeeFraction().compareTo(BigDecimal.ZERO) > 0)
        .findAny()
        .orElse(null);
  }

  private static class BillingRuleException extends RuntimeException {

    BillingRuleException(String s) {
      super(s);
    }

    BillingRuleException(String s, Exception e) {
      super(s, e);
    }
  }
}
