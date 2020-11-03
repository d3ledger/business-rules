/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.provider.impl;

import static iroha.validation.exception.BrvsErrorCode.REGISTRATION_FAILED;
import static iroha.validation.exception.BrvsErrorCode.REGISTRATION_TIMEOUT;
import static iroha.validation.exception.BrvsErrorCode.UNKNOWN_ACCOUNT;
import static iroha.validation.exception.BrvsErrorCode.WRONG_DOMAIN;
import static iroha.validation.utils.ValidationUtils.PROPORTION;
import static iroha.validation.utils.ValidationUtils.fieldValidator;
import static iroha.validation.utils.ValidationUtils.getDomain;
import static iroha.validation.utils.ValidationUtils.sendWithLastResponseWaiting;
import static jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter;

import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import iroha.protocol.Endpoint.TxStatus;
import iroha.validation.exception.BrvsException;
import iroha.validation.transactions.core.provider.RegisteredUsersStorage;
import iroha.validation.transactions.core.provider.RegistrationProvider;
import iroha.validation.transactions.core.provider.UserQuorumProvider;
import iroha.validation.transactions.core.provider.impl.util.RegistrationAwaiterWrapper;
import iroha.validation.utils.ValidationUtils;
import java.io.Closeable;
import java.lang.reflect.Type;
import java.security.Key;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
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
import kotlin.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

/**
 * Class responsible for user related Iroha interaction
 */
public class AccountManager implements UserQuorumProvider, RegistrationProvider, Closeable {

  private static final Logger logger = LoggerFactory.getLogger(AccountManager.class);
  private static final int INITIAL_USER_QUORUM_VALUE = 1;
  private static final int INITIAL_KEYS_AMOUNT = 1;
  private static final Type USER_SIGNATORIES_TYPE_TOKEN = new TypeToken<Set<String>>() {
  }.getType();

  private final ExecutorService executorService = Executors.newCachedThreadPool();

  private final String brvsAccountId;
  private final KeyPair brvsAccountKeyPair;
  private final QueryAPI queryAPI;
  private final String userSignatoriesAttribute;
  private final Set<String> userDomains;
  private final String userAccountsHolderAccount;
  private final String userAccountsSetterAccount;
  private final List<KeyPair> keyPairs;
  private final Set<String> pubKeys;
  private final IrohaQueryHelper irohaQueryHelper;
  private final RegisteredUsersStorage registeredUsersStorage;

  public AccountManager(QueryAPI queryAPI,
      String userSignatoriesAttribute,
      String userDomains,
      String userAccountsHolderAccount,
      String userAccountsSetterAccount,
      List<KeyPair> keyPairs,
      IrohaQueryHelper irohaQueryHelper,
      RegisteredUsersStorage registeredUsersStorage) {

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
    if (Strings.isNullOrEmpty(userAccountsSetterAccount)) {
      throw new IllegalArgumentException(
          "User accounts holder account must not be neither null nor empty");
    }
    if (CollectionUtils.isEmpty(keyPairs)) {
      throw new IllegalArgumentException("Keypairs must not be neither null nor empty");
    }
    Objects.requireNonNull(irohaQueryHelper, "IrohaQueryHelper must not be null");
    Objects.requireNonNull(registeredUsersStorage, "Users storage must not be null");

    this.brvsAccountId = queryAPI.getAccountId();
    this.brvsAccountKeyPair = queryAPI.getKeyPair();
    this.queryAPI = queryAPI;
    this.userSignatoriesAttribute = userSignatoriesAttribute;
    this.userDomains = Arrays.stream(userDomains.split(",")).collect(Collectors.toSet());
    this.userAccountsHolderAccount = userAccountsHolderAccount;
    this.userAccountsSetterAccount = userAccountsSetterAccount;
    this.keyPairs = keyPairs;
    pubKeys = this.keyPairs
        .stream()
        .map(KeyPair::getPublic)
        .map(Key::getEncoded)
        .map(Utils::toHex)
        .map(String::toLowerCase)
        .collect(Collectors.toSet());
    this.irohaQueryHelper = irohaQueryHelper;
    this.registeredUsersStorage = registeredUsersStorage;
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
    TxStatus txStatus = sendWithLastResponseWaiting(
        queryAPI.getApi(),
        Transaction
            .builder(brvsAccountId)
            .setAccountDetail(targetAccount, userSignatoriesAttribute, jsonedKeys)
            .sign(brvsAccountKeyPair)
            .build()
    ).getTxStatus();
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
    TxStatus txStatus = sendWithLastResponseWaiting(
        queryAPI.getApi(),
        Transaction
            .builder(brvsAccountId)
            .setAccountQuorum(targetAccount, quorum)
            .sign(brvsAccountKeyPair)
            .build()
    ).getTxStatus();
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
    final int size = Iterables.size(accounts);
    final RegistrationAwaiterWrapper registrationAwaiterWrapper = new RegistrationAwaiterWrapper(
        new CountDownLatch(size)
    );

    accounts.forEach(account -> executorService
        .submit(new RegistrationRunnable(account, registrationAwaiterWrapper))
    );

    if (!registrationAwaiterWrapper.getCountDownLatch().await(size * 10L, TimeUnit.SECONDS)) {
      throw new BrvsException(
          "Couldn't register accounts within a timeout",
          REGISTRATION_TIMEOUT
      );
    }
    final Exception registrationAwaiterWrapperException = registrationAwaiterWrapper.getException();
    if (registrationAwaiterWrapperException != null) {
      throw new BrvsException(
          registrationAwaiterWrapperException.getMessage(),
          registrationAwaiterWrapperException,
          REGISTRATION_FAILED
      );
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
    TxStatus txStatus = sendWithLastResponseWaiting(
        queryAPI.getApi(),
        transactionBuilder
            .sign(brvsAccountKeyPair)
            .build()
    ).getTxStatus();
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

  @Override
  public boolean isUserAccount(String userAccountId) {
    try {
      final int delimiterIndex = userAccountId.indexOf(accountIdDelimiter);
      final String suffix = userAccountId.substring(delimiterIndex + 1);
      final String replacedSuffix = suffix.replace(".", "_");
      return irohaQueryHelper.getAccountDetails(
          userAccountsHolderAccount,
          userAccountsSetterAccount,
          userAccountId.substring(0, delimiterIndex) + replacedSuffix
      ).get().orElse("").equals(replacedSuffix);
    } catch (Exception e) {
      logger.error("Error during checking user account: " + userAccountId, e);
      return false;
    }
  }

  private String recoverAccountIdFromDetailsEntry(Entry<String, String> entry) {
    String key = entry.getKey();
    String suffix = entry.getValue();
    if (!key.endsWith(suffix)) {
      return null;
    }
    return recoverAccountIdFromDetails(key, suffix);
  }

  private String recoverAccountIdFromDetails(String key, String value) {
    // since accounts stored as key-value pairs of
    // usernamedomain -> domain
    // we need to extract username from the key and add the domain to it separated with @
    final String recoveredSuffix = value.replace('_', '.');
    return key.substring(0, key.lastIndexOf(value))
        .concat(accountIdDelimiter)
        .concat(recoveredSuffix);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void processUnregisteredUserAccounts(Function<Set<String>, Object> method) {
    logger.info("Going to read accounts data from {}", userAccountsHolderAccount);
    try {
      irohaQueryHelper
          .processAccountDetails(
              userAccountsHolderAccount,
              userAccountsSetterAccount,
              detailsSet -> {
                final Set<String> filteredAccounts = detailsSet.stream()
                    .map(this::recoverAccountIdFromDetailsEntry)
                    .filter(accountId -> accountId != null &&
                        userDomains.contains(ValidationUtils.getDomain(accountId)) &&
                        !registeredUsersStorage.contains(accountId))
                    .collect(Collectors.toSet());
                method.apply(filteredAccounts);
                // Kotlin interop
                return Unit.INSTANCE;
              }
          );
    } catch (ErrorResponseException e) {
      throw new IllegalStateException(
          "There is no valid response from Iroha about accounts in " + userAccountsSetterAccount,
          e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<String> getUserDomains() {
    return Collections.unmodifiableSet(userDomains);
  }

  @Override
  public boolean isRegistered(String accountId) {
    return registeredUsersStorage.contains(accountId);
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
      if (registeredUsersStorage.contains(accountId)) {
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
        registeredUsersStorage.add(accountId);
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
