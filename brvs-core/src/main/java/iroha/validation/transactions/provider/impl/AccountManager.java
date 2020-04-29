/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider.impl;

import static iroha.validation.exception.BrvsErrorCode.REGISTRATION_FAILED;
import static iroha.validation.exception.BrvsErrorCode.REGISTRATION_TIMEOUT;
import static iroha.validation.exception.BrvsErrorCode.UNKNOWN_ACCOUNT;
import static iroha.validation.exception.BrvsErrorCode.WRONG_DOMAIN;
import static iroha.validation.utils.ValidationUtils.PROPORTION;
import static iroha.validation.utils.ValidationUtils.fieldValidator;
import static iroha.validation.utils.ValidationUtils.getDomain;
import static jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;
import iroha.protocol.Endpoint;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.exception.BrvsException;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.UserQuorumProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.transactions.provider.impl.util.RegistrationAwaiterWrapper;
import iroha.validation.utils.ValidationUtils;
import java.io.Closeable;
import java.lang.reflect.Type;
import java.security.Key;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.ErrorResponseException;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.TransactionBuilder;
import jp.co.soramitsu.iroha.java.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

/**
 * Class responsible for user related Iroha interaction
 */
public class AccountManager implements UserQuorumProvider, RegistrationProvider, Closeable {

  private static final Logger logger = LoggerFactory.getLogger(AccountManager.class);
  private static final int PUBKEY_LENGTH = 32;
  private static final int INITIAL_USER_QUORUM_VALUE = 1;
  private static final int INITIAL_KEYS_AMOUNT = 1;
  private static final int REGISTRATION_BATCH_SIZE = 500;
  private static final Type USER_SIGNATORIES_TYPE_TOKEN = new TypeToken<Set<String>>() {
  }.getType();

  private final ExecutorService executorService = Executors.newCachedThreadPool();
  private final Set<String> registeredAccounts = ConcurrentHashMap.newKeySet();

  private final String brvsAccountId;
  private final KeyPair brvsAccountKeyPair;
  private final QueryAPI queryAPI;
  private final String userSignatoriesAttribute;
  private final Set<String> userDomains;
  private final String userAccountsHolderAccount;
  private final String brvsInstancesHolderAccount;
  private final List<KeyPair> keyPairs;
  private final Set<String> pubKeys;

  public AccountManager(QueryAPI queryAPI,
      String userSignatoriesAttribute,
      String userDomains,
      String userAccountsHolderAccount,
      String brvsInstancesHolderAccount, List<KeyPair> keyPairs) {

    Objects.requireNonNull(queryAPI, "Query API must not be null");
    if (Strings.isNullOrEmpty(userSignatoriesAttribute)) {
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
    this.userSignatoriesAttribute = userSignatoriesAttribute;
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
  public Set<String> getUserSignatoriesDetail(String targetAccount) {
    try {
      final JsonObject keyNode = ValidationUtils.parser
          .parse(queryAPI.getAccountDetails(targetAccount, brvsAccountId, userSignatoriesAttribute))
          .getAsJsonObject()
          .getAsJsonObject(brvsAccountId);

      if (keyNode == null || keyNode.isJsonNull()
          || keyNode.size() == 0 || keyNode.get(userSignatoriesAttribute).isJsonNull()) {
        logger.warn("Account detail is not set for account: {}", targetAccount);
        return Collections.emptySet();
      }

      return ValidationUtils.gson.fromJson(
          ValidationUtils.irohaUnEscape(
              keyNode.getAsJsonPrimitive(userSignatoriesAttribute).getAsString()
          ),
          USER_SIGNATORIES_TYPE_TOKEN
      );

    } catch (Exception e) {
      throw new IllegalStateException(
          "Unexpected error during user signatories detail retrieval",
          e
      );
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setUserQuorumDetail(String targetAccount,
      Iterable<String> publicKeys) {

    final String jsonedKeys = ValidationUtils.irohaEscape(ValidationUtils.gson.toJson(publicKeys));
    TxStatus txStatus = sendWithLastStatusWaiting(
        Transaction
            .builder(brvsAccountId)
            .setAccountDetail(targetAccount, userSignatoriesAttribute, jsonedKeys)
            .sign(brvsAccountKeyPair)
            .build()
    );
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      throw new IllegalStateException(
          "Could not change user " + targetAccount +
              " signatories detail. Got transaction status: " + txStatus.name()
      );
    }
    logger.info("Successfully set signatories detail: {} - {}", targetAccount, jsonedKeys);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getUserAccountQuorum(String targetAccount) {
    return queryAPI.getAccount(targetAccount).getAccount().getQuorum();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setUserAccountQuorum(String targetAccount, int quorum) {
    if (quorum < 1) {
      throw new IllegalArgumentException("Quorum must be positive, got: " + quorum);
    }
    final int currentQuorum = getUserAccountQuorum(targetAccount);
    final Set<String> userSignatoriesDetail = getUserSignatoriesDetail(targetAccount);
    final int userDetailQuorum =
        userSignatoriesDetail.isEmpty() ? INITIAL_KEYS_AMOUNT : userSignatoriesDetail.size();
    // If we increase user quorum set signatures first to be equal to user keys count
    // Otherwise set quorum first
    if (quorum >= currentQuorum) {
      setBrvsSignatoriesToUser(targetAccount, userDetailQuorum);
      setUserQuorumIroha(targetAccount, quorum);
    } else {
      setUserQuorumIroha(targetAccount, quorum);
      setBrvsSignatoriesToUser(targetAccount, userDetailQuorum);
    }
  }

  private void setUserQuorumIroha(String targetAccount, int quorum) {
    TxStatus txStatus = sendWithLastStatusWaiting(
        Transaction
            .builder(brvsAccountId)
            .setAccountQuorum(targetAccount, quorum)
            .sign(brvsAccountKeyPair)
            .build()
    );
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      throw new IllegalStateException(
          "Could not change user " + targetAccount +
              " quorum. Got transaction status: " + txStatus.name()
      );
    }
    logger.info("Successfully set user quorum: {}, {}", targetAccount, quorum);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getValidQuorumForUserAccount(String accountId) {
    return getValidQuorumForUserAccount(accountId, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void register(String accountId) throws InterruptedException {
    register(Collections.singleton(accountId));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void register(Collection<String> accounts) throws InterruptedException {
    final List<List<String>> partitions = Lists
        .partition(
            new ArrayList<>(accounts),
            REGISTRATION_BATCH_SIZE
        );
    for (List<String> partition : partitions) {
      final int size = Iterables.size(partition);
      final RegistrationAwaiterWrapper registrationAwaiterWrapper = new RegistrationAwaiterWrapper(
          new CountDownLatch(size)
      );

      // TODO after XNET-96 try replacing with fixed thread pool
      partition.forEach(account -> executorService
          .submit(new RegistrationRunnable(account, registrationAwaiterWrapper))
      );

      if (!registrationAwaiterWrapper.getCountDownLatch().await(size * 10L, TimeUnit.SECONDS)) {
        throw new BrvsException(
            "Couldn't register accounts within a timeout",
            REGISTRATION_TIMEOUT
        );
      }
      final Exception registrationAwaiterWrapperException = registrationAwaiterWrapper
          .getException();
      if (registrationAwaiterWrapperException != null) {
        throw new BrvsException(
            registrationAwaiterWrapperException.getMessage(),
            registrationAwaiterWrapperException,
            REGISTRATION_FAILED
        );
      }
    }
  }

  private void setBrvsSignatoriesToUser(String userAccountId, int count) {
    if (count < 1 || count > keyPairs.size()) {
      throw new IllegalArgumentException(
          "Signatories count must be at least 1 and not more than key list size. Got " + count);
    }
    final int containedCount = (int) getAccountSignatories(userAccountId)
        .stream()
        .map(String::toLowerCase)
        .filter(pubKeys::contains)
        .count();
    if (containedCount == count) {
      logger.warn(
          "User account {} has already got {} brvs instance keys.",
          userAccountId,
          count
      );
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
      throw new IllegalStateException(
          "Could not set signatories to user " + userAccountId +
              ". Got transaction status: " + txStatus.name()
      );
    }
  }

  private int getValidQuorumForUserAccount(String accountId, boolean onRegistration) {
    int userQuorum = getUserSignatoriesDetail(accountId).size();
    if (userQuorum == 0 && onRegistration) {
      userQuorum = INITIAL_USER_QUORUM_VALUE;
    }
    return (PROPORTION * userQuorum * getUserAccountQuorum(brvsAccountId));
  }

  private List<String> getAccountSignatories(String targetAccountId) {
    return queryAPI.getSignatories(targetAccountId).getKeysList();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<String> getRegisteredAccounts() {
    return registeredAccounts;
  }

  private <T> Set<T> getAccountsFrom(String accountsHolderAccount,
      Function<Entry, T> processor) {
    logger.info("Going to read accounts data from {}", accountsHolderAccount);
    Set<T> resultSet = new HashSet<>();
    try {
      JsonElement rootNode = ValidationUtils.parser
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
    final String recoveredSuffix = suffix.replace('_', '.');
    return key.substring(0, key.lastIndexOf(suffix))
        .concat(accountIdDelimiter)
        .concat(recoveredSuffix);
  }

  private BrvsData brvsAccountProcessor(Entry<String, JsonPrimitive> entry) {
    String pubkey = entry.getKey();
    String hostname = entry.getValue().getAsString();
    if (pubkey.length() != PUBKEY_LENGTH) {
      logger.warn("Expected hostname-pubkey pair. Got {} : {}", hostname, pubkey);
      return null;
    }
    return new BrvsData(hostname, pubkey);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<String> getUserAccounts() {
    return getAccountsFrom(userAccountsHolderAccount, this::userAccountProcessor);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<BrvsData> getBrvsInstances() {
    return getAccountsFrom(brvsInstancesHolderAccount, this::brvsAccountProcessor);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<String> getUserDomains() {
    return Collections.unmodifiableSet(userDomains);
  }

  private Endpoint.TxStatus sendWithLastStatusWaiting(
      TransactionOuterClass.Transaction transaction) {
    return queryAPI.getApi().transaction(
        transaction,
        ValidationUtils.subscriptionStrategy
    ).blockingLast().getTxStatus();
  }

  @Override
  public void close() {
    executorService.shutdownNow();
  }

  /**
   * Intermediary runnable-wrapper for brvs registration
   */
  private class RegistrationRunnable implements Runnable {

    private final String accountId;
    private final RegistrationAwaiterWrapper registrationAwaiterWrapper;

    RegistrationRunnable(String accountId, RegistrationAwaiterWrapper registrationAwaiterWrapper) {
      this.accountId = accountId;
      this.registrationAwaiterWrapper = registrationAwaiterWrapper;
    }

    private void doRegister(String accountId) {
      logger.info("Going to register {}", accountId);
      fieldValidator.checkAccountId(accountId);
      if (registeredAccounts.contains(accountId)) {
        logger.warn("Account {} has already been registered, omitting", accountId);
        return;
      }
      if (!userDomains.contains(getDomain(accountId))) {
        throw new BrvsException(
            "The BRVS instance is not permitted to process the domain specified: " +
                getDomain(accountId) + ".", WRONG_DOMAIN);
      }
      if (!existsInIroha(accountId)) {
        throw new BrvsException(
            "Account " + accountId
                + " does not exist or an error during querying process occurred.",
            UNKNOWN_ACCOUNT
        );
      }
      final Set<String> userSignatories = getUserSignatoriesDetail(accountId);
      try {
        setBrvsSignatoriesToUser(accountId,
            CollectionUtils.isEmpty(userSignatories) ? INITIAL_KEYS_AMOUNT : userSignatories.size()
        );
        modifyQuorumOnRegistration(accountId);
        registeredAccounts.add(accountId);
        logger.info("Successfully registered {}", accountId);
      } catch (Exception e) {
        throw new BrvsException(
            "Error during brvs user registration occurred. Account id: " + accountId,
            e,
            REGISTRATION_FAILED
        );
      }
    }

    private void modifyQuorumOnRegistration(String userAccountId) {
      final int quorum = getValidQuorumForUserAccount(userAccountId, true);
      if (getUserAccountQuorum(userAccountId) == quorum) {
        logger.warn("Account {} already has valid quorum: {}", userAccountId, quorum);
        return;
      }
      setUserAccountQuorum(
          userAccountId,
          quorum
      );
    }

    private boolean existsInIroha(String userAccountId) {
      return queryAPI.getAccount(userAccountId).hasAccount();
    }

    @Override
    public void run() {
      try {
        doRegister(accountId);
      } catch (Exception e) {
        registrationAwaiterWrapper.setException(e);
      } finally {
        registrationAwaiterWrapper.getCountDownLatch().countDown();
      }
    }
  }
}
