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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class BillingRule implements Rule {

  private static final String QUEUE_NAME = "brvs_billing_updates";
  private static final String TRANSFER_BILLING_ACCOUNT_NAME = "transfer_billing";
  private static final String CUSTODY_BILLING_ACCOUNT_NAME = "custody_billing";
  private static final String ACCOUNT_CREATION_BILLING_ACCOUNT_NAME = "account_creation_billing";
  private static final String EXCHANGE_BILLING_ACCOUNT_NAME = "exchange_billing";
  private static final String WITHDRAWAL_BILLING_ACCOUNT_NAME = "withdrawal_billing";
  private static final Map<String, BillingTypeEnum> feeAccounts = new HashMap<String, BillingTypeEnum>() {{
    put(TRANSFER_BILLING_ACCOUNT_NAME, BillingTypeEnum.TRANSFER);
    put(CUSTODY_BILLING_ACCOUNT_NAME, BillingTypeEnum.CUSTODY);
    put(ACCOUNT_CREATION_BILLING_ACCOUNT_NAME, BillingTypeEnum.ACCOUNT_CREATION);
    put(EXCHANGE_BILLING_ACCOUNT_NAME, BillingTypeEnum.EXCHANGE);
    put(WITHDRAWAL_BILLING_ACCOUNT_NAME, BillingTypeEnum.WITHDRAWAL);
  }};
  private static final JsonParser jsonParser = new JsonParser();
  private static final TransferAsset FEE_NOT_NEEDED = TransferAsset.getDefaultInstance();
  private static final BigDecimal NO_FEE = BigDecimal.ZERO;

  private boolean isRunning = false;
  private final String getBillingURL;
  private final String rmqHost;
  private final int rmqPort;
  private final String rmqExchange;
  private final String rmqRoutingKey;
  private final Gson gson = new Gson();
  private final Set<BillingInfo> cache = new HashSet<>();

  public BillingRule(String getBillingURL,
      String rmqHost,
      int rmqPort,
      String rmqExchange,
      String rmqRoutingKey) throws IOException {

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

    this.getBillingURL = getBillingURL;
    this.rmqHost = rmqHost;
    this.rmqPort = rmqPort;
    this.rmqExchange = rmqExchange;
    this.rmqRoutingKey = rmqRoutingKey;
    runCacheUpdater();
  }

  private void runCacheUpdater() throws IOException {
    if (!isRunning) {
      isRunning = true;
    }
    readBillingOnStartup();
    getMqUpdatesObservable().subscribeOn(Schedulers.from(Executors.newSingleThreadExecutor()))
        .subscribe(update -> {
              final BillingInfo currentBillingInfo = cache
                  .stream()
                  .filter(entry -> entry.equals(update))
                  .findAny()
                  .orElse(null);
              if (currentBillingInfo == null
                  || currentBillingInfo.getUpdated().isBefore(update.getUpdated())) {
                // Will be replaced, see BillingInfo#hashCode
                cache.add(update);
              }
            }
        );
  }

  private void readBillingOnStartup() throws IOException {
    final JsonObject root = jsonParser.parse(executeGetRequest()).getAsJsonObject();
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
      channel.exchangeDeclare(rmqExchange, BuiltinExchangeType.FANOUT, true);
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
            feeAccounts.keySet().contains(BillingInfo.getName(transferAsset.getDestAccountId())))
        );

    List<TransferAsset> fees = transactionsGroups.get(true);

    for (TransferAsset transferAsset : transactionsGroups.get(false)) {
      final TransferAsset feeFor = findFeeFor(transferAsset, fees);
      if (feeFor == null) {
        return false;
      }
      // To prevent case when there are two identical transfers and only one fee
      fees.remove(feeFor);
    }
    return true;
  }

  private TransferAsset findFeeFor(TransferAsset transfer, List<TransferAsset> fees) {
    final String srcAccountId = transfer.getSrcAccountId();
    final String assetId = transfer.getAssetId();
    final BigDecimal amount = new BigDecimal(transfer.getAmount());

    for (TransferAsset fee : fees) {
      final BigDecimal feeFraction = getFeeFractionFor(fee.getDestAccountId(), fee.getAssetId());
      if (feeFraction.equals(NO_FEE)) {
        return FEE_NOT_NEEDED;
      }
      if (fee.getSrcAccountId().equals(srcAccountId)
          && fee.getAssetId().equals(assetId)
          && new BigDecimal(fee.getAmount())
          .equals(amount.multiply(feeFraction))) {
        return fee;
      }
    }
    return null;
  }

  private BigDecimal getFeeFractionFor(String accountId, String asset) {
    return cache
        .stream()
        .filter(entry -> entry.getDomain().equals(BillingInfo.getDomain(accountId))
            && entry.getBillingType().equals(feeAccounts.get(BillingInfo.getName(accountId)))
            && entry.getAsset().equals(asset))
        .findAny()
        .map(BillingInfo::getFeeFraction)
        .orElse(NO_FEE);
  }

  private static class BillingRuleException extends RuntimeException {

    BillingRuleException(String s) {
      super(s);
    }
  }
}
