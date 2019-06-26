/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider.impl;

import static iroha.validation.utils.ValidationUtils.PROPORTION;

import com.google.common.base.Strings;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import iroha.protocol.Endpoint;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.UserQuorumProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.utils.ValidationUtils;
import java.security.Key;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.ErrorResponseException;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.TransactionBuilder;
import jp.co.soramitsu.iroha.java.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

public class AccountManager implements UserQuorumProvider, RegistrationProvider {

  private static final Logger logger = LoggerFactory.getLogger(AccountManager.class);
  // Not to let BRVS to take it in processing
  // Max quorum is 128
  private static final int UNREACHABLE_QUORUM = 129;
  private static final Pattern ACCOUN_ID_PATTERN = Pattern.compile("[a-z0-9_]{1,32}@[a-z0-9]+");
  private static final int PUBKEY_LENGTH = 32;
  private static final int INITIAL_USER_QUORUM_VALUE = 1;
  private static final JsonParser parser = new JsonParser();
  private static final int INITIAL_KEYS_AMOUNT = 1;

  private final Scheduler scheduler = Schedulers.from(Executors.newCachedThreadPool());
  private final Set<String> registeredAccounts = ConcurrentHashMap.newKeySet();

  private final String brvsAccountId;
  private final KeyPair brvsAccountKeyPair;
  private final QueryAPI queryAPI;
  private final String userQuorumAttribute;
  private final Set<String> userDomains;
  private final String userAccountsHolderAccount;
  private final String brvsInstancesHolderAccount;
  private final List<KeyPair> keyPairs;
  private final Set<String> pubKeys;

  public AccountManager(QueryAPI queryAPI,
      String userQuorumAttribute,
      String userDomains,
      String userAccountsHolderAccount,
      String brvsInstancesHolderAccount, List<KeyPair> keyPairs) {

    Objects.requireNonNull(queryAPI, "Query API must not be null");
    if (Strings.isNullOrEmpty(userQuorumAttribute)) {
      throw new IllegalArgumentException(
          "User quorum attribute name must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(userDomains)) {
      throw new IllegalArgumentException("User domains string must not be null nor empty");
    }
    if (Strings.isNullOrEmpty(userAccountsHolderAccount)) {
      throw new IllegalArgumentException(
          "User accounts holder account must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(brvsInstancesHolderAccount)) {
      throw new IllegalArgumentException(
          "Brvs instances holder account must not be neither null nor empty");
    }
    if (CollectionUtils.isEmpty(keyPairs)) {
      throw new IllegalArgumentException("Keypairs must not be neither null nor empty");
    }

    this.brvsAccountId = queryAPI.getAccountId();
    this.brvsAccountKeyPair = queryAPI.getKeyPair();
    this.queryAPI = queryAPI;
    this.userQuorumAttribute = userQuorumAttribute;
    this.userDomains = Arrays.stream(userDomains.split(",")).collect(Collectors.toSet());
    this.userAccountsHolderAccount = userAccountsHolderAccount;
    this.brvsInstancesHolderAccount = brvsInstancesHolderAccount;
    this.keyPairs = keyPairs;
    pubKeys = this.keyPairs
        .stream()
        .map(KeyPair::getPublic)
        .map(Key::getEncoded)
        .map(Utils::toHex)
        .map(String::toLowerCase)
        .collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getUserQuorumDetail(String targetAccount) {
    try {
      return Integer.parseInt(
          queryAPI.getAccountDetails(targetAccount, brvsAccountId, userQuorumAttribute)
              .split("\"")[5]);
    } catch (ErrorResponseException e) {
      logger
          .error("Account detail is not set for account: " + targetAccount, e);
      return UNREACHABLE_QUORUM;
    } catch (ArrayIndexOutOfBoundsException e) {
      logger.warn(
          "User quorum details was not set previously yet. Please set it using appropriate services. Account: "
              + targetAccount);
      return UNREACHABLE_QUORUM;
    } catch (NumberFormatException e) {
      logger.error("Error occurred parsing quorum details for " + targetAccount, e);
      return UNREACHABLE_QUORUM;
    } catch (Exception e) {
      logger.error("Unknown exception occurred retrieving quorum data", e);
      return UNREACHABLE_QUORUM;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setUserQuorumDetail(String targetAccount, int quorum, long creationTimeMillis) {
    TxStatus txStatus = sendWithLastStatusWaiting(
        Transaction
            .builder(brvsAccountId, creationTimeMillis)
            .setAccountDetail(targetAccount, userQuorumAttribute, String.valueOf(quorum))
            .sign(brvsAccountKeyPair)
            .build()
    );
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      logger.error("Could not change user " + targetAccount +
          " quorum (ACC_DETAILS). Got transaction status: " + txStatus.name()
      );
      throw new IllegalStateException(
          "Could not change user " + targetAccount +
              " quorum (ACC_DETAILS). Got transaction status: " + txStatus.name()
      );
    }
    logger.info("Successfully set user quorum DETAIL: " + targetAccount + ", " + quorum);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setUserAccountQuorum(String targetAccount, int quorum, long createdTimeMillis) {
    if (quorum < 1) {
      throw new IllegalArgumentException("Quorum must be positive, got: " + quorum);
    }
    final int currentQuorum = getAccountQuorum(targetAccount);
    final int userQuorumDetail = getUserQuorumDetail(targetAccount);
    // If we increase user quorum set signatures first to be equal to user keys count
    // Otherwise set quorum first
    if (quorum >= currentQuorum) {
      setBrvsSignatoriesToUser(targetAccount, userQuorumDetail);
      setUserQuorumIroha(targetAccount, quorum, createdTimeMillis);
    } else {
      setUserQuorumIroha(targetAccount, quorum, createdTimeMillis);
      setBrvsSignatoriesToUser(targetAccount, userQuorumDetail);
    }
  }

  private void setUserQuorumIroha(String targetAccount, int quorum, long createdTimeMillis) {
    TxStatus txStatus = sendWithLastStatusWaiting(
        Transaction
            .builder(brvsAccountId, createdTimeMillis)
            .setAccountQuorum(targetAccount, quorum)
            .sign(brvsAccountKeyPair)
            .build()
    );
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      logger.error("Could not change user " + targetAccount +
          " quorum. Got transaction status: " + txStatus.name()
      );
      throw new IllegalStateException(
          "Could not change user " + targetAccount +
              " quorum. Got transaction status: " + txStatus.name()
      );
    }
    logger.info("Successfully set user quorum: " + targetAccount + ", " + quorum);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getValidQuorumForUserAccount(String accountId) {
    return getValidQuorumForUserAccount(accountId, false);
  }

  private boolean existsInIroha(String userAccountId) {
    return queryAPI.getAccount(userAccountId).hasAccount();
  }

  private boolean hasValidFormat(String accountId) {
    return ACCOUN_ID_PATTERN.matcher(accountId).matches();
  }

  private String getDomain(String accountId) {
    return accountId.split("@")[1];
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void register(String accountId) {
    scheduler.scheduleDirect(new RegistrationRunnable(accountId));
  }

  private void doRegister(String accountId) {
    logger.info("Going to register " + accountId);
    if (!hasValidFormat(accountId)) {
      throw new IllegalArgumentException(
          "Invalid account format [" + accountId + "]. Use 'username@domain'.");
    }
    if (!userDomains.contains(getDomain(accountId))) {
      throw new IllegalArgumentException(
          "The BRVS instance is not permitted to process the domain specified: " +
              getDomain(accountId) + ".");
    }
    if (!existsInIroha(accountId)) {
      throw new IllegalArgumentException(
          "Account " + accountId + " does not exist or an error during querying process occurred.");
    }
    int quorum = getUserQuorumDetail(accountId);
    if (quorum == UNREACHABLE_QUORUM) {
      quorum = INITIAL_KEYS_AMOUNT;
      setUserQuorumDetail(accountId, quorum, System.currentTimeMillis());
    }
    try {
      setBrvsSignatoriesToUser(accountId, quorum);
    } catch (IllegalStateException e) {
      logger.warn("Probably, the account " + accountId + " was registered before", e);
    } catch (Exception e) {
      logger.error("Error during brvs user registration occurred. Account id: " + accountId, e);
      throw e;
    }
    try {
      modifyQuorumOnRegistration(accountId);
    } catch (Exception e) {
      logger.error("Error during brvs user registration occurred. Account id: " + accountId, e);
      throw e;
    }
    registeredAccounts.add(accountId);
    logger.info("Successfully registered " + accountId);
  }

  private void setBrvsSignatoriesToUser(String userAccountId, int count) {
    if (count < 1 || count > keyPairs.size()) {
      throw new IllegalArgumentException(
          "Signatories count must be at least 1 and not more than key list size.");
    }
    final int containedCount = (int) getAccountSignatories(userAccountId)
        .stream()
        .map(String::toLowerCase)
        .filter(pubKeys::contains)
        .count();
    if (containedCount == count) {
      logger.warn(
          "User account " + userAccountId + " has already got " + count + " brvs instance keys.");
      return;
    }
    final TransactionBuilder transactionBuilder = Transaction.builder(brvsAccountId);
    if (count > containedCount) {
      for (int i = containedCount; i < count; i++) {
        transactionBuilder.addSignatory(userAccountId, keyPairs.get(i).getPublic());
      }
    } else {
      for (int i = containedCount; i > count; i--) {
        transactionBuilder.removeSignatory(userAccountId, keyPairs.get(i - 1).getPublic());
      }
    }
    TxStatus txStatus = sendWithLastStatusWaiting(
        transactionBuilder
            .sign(brvsAccountKeyPair)
            .build()
    );
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      logger.error("Could not set signatories to user " + userAccountId +
          ". Got transaction status: " + txStatus.name()
      );
      throw new IllegalStateException(
          "Could not set signatories to user " + userAccountId +
              ". Got transaction status: " + txStatus.name()
      );
    }
  }

  private synchronized void modifyQuorumOnRegistration(String userAccountId) {
    setUserAccountQuorum(
        userAccountId,
        getValidQuorumForUserAccount(userAccountId, true),
        System.currentTimeMillis()
    );
  }

  private int getValidQuorumForUserAccount(String accountId, boolean onRegistration) {
    int userQuorum = getUserQuorumDetail(accountId);
    if (userQuorum == UNREACHABLE_QUORUM && onRegistration) {
      userQuorum = INITIAL_USER_QUORUM_VALUE;
    }
    return (PROPORTION * userQuorum * getAccountQuorum(brvsAccountId));
  }

  private int getAccountQuorum(String targetAccountId) {
    return queryAPI.getAccount(targetAccountId).getAccount().getQuorum();
  }

  private List<String> getAccountSignatories(String targetAccountId) {
    return queryAPI.getSignatories(targetAccountId).getKeysList();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterable<String> getRegisteredAccounts() {
    return registeredAccounts;
  }

  private <T> Iterable<T> getAccountsFrom(String accountsHolderAccount,
      Function<Entry, T> processor) {
    logger.info("Going to read accounts data from " + accountsHolderAccount);
    Set<T> resultSet = new HashSet<>();
    try {
      JsonElement rootNode = parser
          .parse(queryAPI
              .getAccount(accountsHolderAccount)
              .getAccount()
              .getJsonData()
          );
      rootNode.getAsJsonObject().entrySet().forEach(accountSetter ->
          accountSetter
              .getValue()
              .getAsJsonObject()
              .entrySet()
              .forEach(entry -> {
                    T candidate = processor.apply(entry);
                    if (candidate != null) {
                      resultSet.add(candidate);
                    }
                  }
              )
      );
      return resultSet;
    } catch (ErrorResponseException e) {
      throw new IllegalStateException(
          "There is no valid response from Iroha about accounts in " + accountsHolderAccount, e);
    }
  }

  private String userAccountProcessor(Entry<String, JsonPrimitive> entry) {
    String key = entry.getKey();
    String suffix = entry.getValue().getAsString();
    if (!key.endsWith(suffix)) {
      return null;
    }
    // since accounts stored as key-value pairs of
    // usernamedomain -> domain
    // we need to extract username from the key and add the domain to it separated with @
    return key.substring(0, key.lastIndexOf(suffix)).concat("@").concat(suffix);
  }

  private BrvsData brvsAccountProcessor(Entry<String, JsonPrimitive> entry) {
    String pubkey = entry.getKey();
    String hostname = entry.getValue().getAsString();
    if (pubkey.length() != PUBKEY_LENGTH) {
      logger.warn("Expected hostname-pubkey pair. Got " + hostname + " : " + pubkey);
      return null;
    }
    return new BrvsData(hostname, pubkey);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterable<String> getUserAccounts() {
    return getAccountsFrom(userAccountsHolderAccount, this::userAccountProcessor);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterable<BrvsData> getBrvsInstances() {
    return getAccountsFrom(brvsInstancesHolderAccount, this::brvsAccountProcessor);
  }

  private Endpoint.TxStatus sendWithLastStatusWaiting(
      TransactionOuterClass.Transaction transaction) {
    return queryAPI.getApi().transaction(
        transaction,
        ValidationUtils.subscriptionStrategy
    ).blockingLast().getTxStatus();
  }

  /**
   * Intermediary runnable-wrapper for brvs registration
   */
  private class RegistrationRunnable implements Runnable {

    private final String accountId;

    RegistrationRunnable(String accountId) {
      this.accountId = accountId;
    }

    @Override
    public void run() {
      doRegister(accountId);
    }
  }
}
