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
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.billing.BillingInfo.BillingTypeEnum;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.detail.Const;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BillingRule implements Rule {

  private static final Logger logger = LoggerFactory.getLogger(BillingRule.class);
  private static final String SEPARATOR = ",";
  private static final String QUEUE_NAME = "brvs_billing_updates";
  private static final String TRANSFER_BILLING_ACCOUNT_NAME = "transfer_billing";
  private static final String CUSTODY_BILLING_ACCOUNT_NAME = "custody_billing";
  private static final String ACCOUNT_CREATION_BILLING_ACCOUNT_NAME = "account_creation_billing";
  private static final String EXCHANGE_BILLING_ACCOUNT_NAME = "exchange_billing";
  private static final String WITHDRAWAL_BILLING_ACCOUNT_NAME = "withdrawal_billing";
  private static final Map<BillingTypeEnum, String> feeTypesAccounts = new HashMap<BillingTypeEnum, String>() {{
    put(BillingTypeEnum.TRANSFER, TRANSFER_BILLING_ACCOUNT_NAME);
    put(BillingTypeEnum.CUSTODY, CUSTODY_BILLING_ACCOUNT_NAME);
    put(BillingTypeEnum.ACCOUNT_CREATION, ACCOUNT_CREATION_BILLING_ACCOUNT_NAME);
    put(BillingTypeEnum.EXCHANGE, EXCHANGE_BILLING_ACCOUNT_NAME);
    put(BillingTypeEnum.WITHDRAWAL, WITHDRAWAL_BILLING_ACCOUNT_NAME);
  }};
  private static final JsonParser jsonParser = new JsonParser();
  private static final Gson gson = new Gson();

  private boolean isRunning;
  private final String getBillingURL;
  private final String rmqHost;
  private final int rmqPort;
  private final String rmqExchange;
  private final String rmqRoutingKey;
  private final Set<String> userDomains;
  private final Set<String> depositAccounts;
  private final Set<String> withdrawalAccounts;
  private final Set<BillingInfo> cache = new HashSet<>();

  public BillingRule(String getBillingURL,
      String rmqHost,
      int rmqPort,
      String rmqExchange,
      String rmqRoutingKey,
      String userDomains,
      String depositAccounts,
      String withdrawalAccounts) throws IOException {

    if (Strings.isNullOrEmpty(getBillingURL)) {
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
    if (Strings.isNullOrEmpty(withdrawalAccounts)) {
      throw new IllegalArgumentException(
          "Withdrawal accounts key must not be neither null nor empty");
    }

    this.getBillingURL = getBillingURL;
    this.rmqHost = rmqHost;
    this.rmqPort = rmqPort;
    this.rmqExchange = rmqExchange;
    this.rmqRoutingKey = rmqRoutingKey;
    this.userDomains = new HashSet<>(Arrays.asList(userDomains.split(SEPARATOR)));
    this.depositAccounts = new HashSet<>(Arrays.asList(depositAccounts.split(SEPARATOR)));
    this.withdrawalAccounts = new HashSet<>(Arrays.asList(withdrawalAccounts.split(SEPARATOR)));
    runCacheUpdater();
  }

  private void runCacheUpdater() throws IOException {
    if (isRunning) {
      logger.warn("Cache updater is already running");
      return;
    }
    isRunning = true;
    readBillingOnStartup();
    getMqUpdatesObservable().subscribeOn(Schedulers.from(Executors.newSingleThreadExecutor()))
        .subscribe(update -> {
              logger.info("Got billing data update from MQ: " + update.toString());
              final BillingInfo currentBillingInfo = cache
                  .stream()
                  // equality check does not check the date
                  .filter(entry -> entry.equals(update))
                  .findAny()
                  .orElse(null);
              if (currentBillingInfo == null
                  || currentBillingInfo.getUpdated() < update.getUpdated()) {
                cache.remove(update);
                cache.add(update);
              }
            }
        );
    logger.info("Billing cache updater has been started");
  }

  private void readBillingOnStartup() throws IOException {
    final JsonObject root = jsonParser.parse(executeGetRequest()).getAsJsonObject();
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

  private String executeGetRequest() throws IOException {
    URL url = new URL(getBillingURL);
    HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
    final int responseCode = urlConnection.getResponseCode();
    if (responseCode != 200) {
      throw new BillingRuleException(
          "Couldn't request primary billing information. Response code is " + responseCode
      );
    }

    BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
    String inputLine;
    StringBuilder response = new StringBuilder();
    while ((inputLine = in.readLine()) != null) {
      response.append(inputLine);
    }
    in.close();
    return response.toString();
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

  @Override
  public boolean isSatisfiedBy(Transaction transaction) {
    // Group 'true' means fee transfers
    // Group 'false' means original transfers
    final Map<Boolean, List<TransferAsset>> transactionsGroups = transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasTransferAsset)
        .map(Command::getTransferAsset)
        .collect(Collectors.groupingBy(transferAsset ->
            feeTypesAccounts.values()
                .contains(BillingInfo.getName(transferAsset.getDestAccountId())))
        );

    List<TransferAsset> fees = transactionsGroups.get(true);
    final String userDomain = BillingInfo
        .getDomain(transaction.getPayload().getReducedPayload().getCreatorAccountId());
    final boolean isBatch = transaction.getPayload().getBatch().getReducedHashesCount() > 1;

    for (TransferAsset transferAsset : transactionsGroups.get(false)) {
      final BillingTypeEnum originalType = getBillingType(transferAsset, isBatch);
      if (originalType != null) {
        final BillingInfo billingInfo = getBillingInfoFor(userDomain,
            transferAsset.getAssetId(),
            originalType
        );
        // Not billable operation
        if (billingInfo == null) {
          continue;
        }

        final TransferAsset feeCandidate = filterFee(transferAsset, fees, billingInfo);
        // If operation is billable but there is no corresponding fee attached
        if (feeCandidate == null) {
          return false;
        }
        // To prevent case when there are two identical operations and only one fee
        fees.remove(feeCandidate);
      }
    }
    return true;
  }

  private TransferAsset filterFee(TransferAsset transfer,
      List<TransferAsset> fees,
      BillingInfo billingInfo) {

    final String srcAccountId = transfer.getSrcAccountId();
    final String assetId = transfer.getAssetId();
    final BigDecimal amount = new BigDecimal(transfer.getAmount());
    final String destAccountName = feeTypesAccounts.get(billingInfo.getBillingType())
        .concat(Const.accountIdDelimiter)
        .concat(billingInfo.getDomain());

    for (TransferAsset fee : fees) {
      if (fee.getSrcAccountId().equals(srcAccountId)
          && fee.getAssetId().equals(assetId)
          && fee.getDestAccountId().equals(destAccountName)
          && new BigDecimal(fee.getAmount())
          .compareTo(amount.multiply(billingInfo.getFeeFraction())) == 0) {
        return fee;
      }
    }
    return null;
  }

  private BillingTypeEnum getBillingType(TransferAsset transfer, boolean isBatch) {
    final String srcAccountId = transfer.getSrcAccountId();
    final String destAccountId = transfer.getDestAccountId();
    final String srcDomain = BillingInfo.getDomain(srcAccountId);
    final String destDomain = BillingInfo.getDomain(destAccountId);

    if (depositAccounts.contains(srcAccountId)
        && userDomains.contains(destDomain)) {
      // TODO Not yet decided for sure
      return BillingTypeEnum.ACCOUNT_CREATION;
    }
    if (withdrawalAccounts.contains(destAccountId)
        && userDomains.contains(destDomain)) {
      return BillingTypeEnum.WITHDRAWAL;
    }
    if (userDomains.contains(srcDomain)
        && userDomains.contains(destDomain)) {
      if (isBatch) {
        return BillingTypeEnum.EXCHANGE;
      }
      return BillingTypeEnum.TRANSFER;
    }
    return null;
  }

  private BillingInfo getBillingInfoFor(String domain, String asset, BillingTypeEnum originalType) {
    return cache
        .stream()
        .filter(entry -> entry.getDomain().equals(domain)
            && entry.getBillingType().equals(originalType)
            && entry.getAsset().equals(asset))
        .findAny()
        .orElse(null);
  }

  private static class BillingRuleException extends RuntimeException {

    BillingRuleException(String s) {
      super(s);
    }
  }
}
