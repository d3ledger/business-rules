package iroha.validation.transactions.provider.impl;

import com.google.common.base.Strings;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.reactivex.Observable;
import iroha.protocol.Endpoint;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.UserQuorumProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.xml.bind.DatatypeConverter;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.TransactionBuilder;
import jp.co.soramitsu.iroha.java.detail.BuildableAndSignable;
import jp.co.soramitsu.iroha.java.subscription.SubscriptionStrategy;
import jp.co.soramitsu.iroha.java.subscription.WaitForTerminalStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AccountManager implements UserQuorumProvider, RegistrationProvider {

  private static final Logger logger = LoggerFactory.getLogger(AccountManager.class);
  // Not to let BRVS to take it in processing
  // Max quorum is 128
  private static final int UNREACHABLE_QUORUM = 129;
  private static final Pattern ACCOUN_ID_PATTERN = Pattern.compile("[a-z0-9_]{1,32}@[a-z0-9]+");
  private static final float PROPORTION = 2f / 3;
  private static final int PUBKEY_LENGTH = 32;
  private static final int INITIAL_QUORUM_VALUE = 1;
  private static final JsonParser parser = new JsonParser();
  private static final SubscriptionStrategy subscriptionStrategy = new WaitForTerminalStatus(
      Arrays.asList(
          TxStatus.STATELESS_VALIDATION_FAILED,
          TxStatus.STATEFUL_VALIDATION_FAILED,
          TxStatus.COMMITTED,
          TxStatus.MST_EXPIRED,
          TxStatus.REJECTED,
          TxStatus.UNRECOGNIZED
      )
  );

  private final Set<String> registeredAccounts = new HashSet<>();

  private final String accountId;
  private final KeyPair keyPair;
  private final IrohaAPI irohaAPI;
  private final String userQuorumAttribute;
  private final String userAccountsHolderAccount;
  private final String brvsInstancesHolderAccount;

  public AccountManager(String accountId,
      KeyPair keyPair,
      IrohaAPI irohaAPI,
      String userQuorumAttribute,
      String userAccountsHolderAccount,
      String brvsInstancesHolderAccount) {
    if (Strings.isNullOrEmpty(accountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null nor empty");
    }
    Objects.requireNonNull(keyPair, "Key pair must not be null");
    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    if (Strings.isNullOrEmpty(userQuorumAttribute)) {
      throw new IllegalArgumentException(
          "User quorum attribute name must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(userAccountsHolderAccount)) {
      throw new IllegalArgumentException(
          "User accounts holder account must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(brvsInstancesHolderAccount)) {
      throw new IllegalArgumentException(
          "Brvs instances holder account must not be neither null nor empty");
    }
    this.accountId = accountId;
    this.keyPair = keyPair;
    this.irohaAPI = irohaAPI;
    this.userQuorumAttribute = userQuorumAttribute;
    this.userAccountsHolderAccount = userAccountsHolderAccount;
    this.brvsInstancesHolderAccount = brvsInstancesHolderAccount;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getUserQuorum(String targetAccount) {
    QueryResponse queryResponse = irohaAPI.query(
        Query
            .builder(accountId, 1L)
            .getAccountDetail(targetAccount, accountId, userQuorumAttribute)
            .buildSigned(keyPair));
    if (!queryResponse.hasAccountDetailResponse()) {
      logger.error("Account detail is not set for account: " + targetAccount);
      return UNREACHABLE_QUORUM;
    }
    try {
      return Integer.parseInt(queryResponse.getAccountDetailResponse().getDetail().split("\"")[5]);
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

  @Override
  public void setUserQuorum(String targetAccount, int quorum) {
    TxStatus txStatus = sendWithLastStatusWaiting(
        Transaction
            .builder(accountId)
            .setAccountDetail(targetAccount, userQuorumAttribute, String.valueOf(quorum))
            .sign(keyPair)
            .build()
    ).blockingLast().getTxStatus();
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      logger.error("Could not change user " + targetAccount +
          " quorum (ACC_DETAILS). Got transaction status: " + txStatus.name()
      );
      throw new IllegalStateException(
          "Could not change user " + targetAccount +
              " quorum (ACC_DETAILS). Got transaction status: " + txStatus.name()
      );
    }
  }

  private boolean existsInIroha(String userAccountId) {
    return irohaAPI
        .query(
            Query.builder(accountId, 1L)
                .getAccount(userAccountId)
                .buildSigned(keyPair)
        )
        .getAccountResponse()
        .hasAccount();
  }


  private boolean hasValidFormat(String accountId) {
    return ACCOUN_ID_PATTERN.matcher(accountId).matches();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized void register(String accountId) {
    logger.info("Going to register " + accountId);
    if (registeredAccounts.contains(accountId)) {
      throw new IllegalArgumentException("User " + accountId + " is already registered.");
    }
    if (!hasValidFormat(accountId)) {
      throw new IllegalArgumentException(
          "Invalid account format [" + accountId + "]. Use 'username@domain'.");
    }
    if (!existsInIroha(accountId)) {
      throw new IllegalArgumentException("Account " + accountId + " does not exist.");
    }
    try {
      // if this is the only brvs in the network
      boolean sign = getBrvsAccountQuorum() == 1;
      addSignatoryToUser(accountId, sign);
      modifyQuorumOnRegistration(accountId, sign);
    } catch (Exception e) {
      logger.error("Error during brvs user registration occurred. Account id: " + accountId, e);
      throw e;
    }
    registeredAccounts.add(accountId);
    logger.info("Successfully registered " + accountId);
  }

  private void addSignatoryToUser(String userAccountId, boolean sign) {
    TxStatus txStatus = sendWithLastStatusWaiting(
        decideOnSigning(
            Transaction
                .builder(accountId)
                .addSignatory(userAccountId, keyPair.getPublic()),
            sign
        ).build()
    ).blockingLast().getTxStatus();
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      logger.error("Could not register user " + userAccountId +
          ". Got transaction status: " + txStatus.name()
      );
      throw new IllegalStateException(
          "Could not register user " + userAccountId +
              ". Got transaction status: " + txStatus.name()
      );
    }
  }

  private synchronized void modifyQuorumOnRegistration(String userAccountId, boolean sign) {
    TxStatus txStatus = sendWithLastStatusWaiting(
        decideOnSigning(
            Transaction
                .builder(accountId)
                .setAccountQuorum(userAccountId, getValidQuorumForUserAccount(userAccountId)),
            sign
        ).build()
    ).blockingLast().getTxStatus();
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      logger.error("Could not change user " + userAccountId +
          " quorum. Got transaction status: " + txStatus.name()
      );
      throw new IllegalStateException(
          "Could not change user " + userAccountId +
              " quorum. Got transaction status: " + txStatus.name()
      );
    }
    setUserQuorum(userAccountId, INITIAL_QUORUM_VALUE);
  }

  private BuildableAndSignable<TransactionOuterClass.Transaction> decideOnSigning(
      TransactionBuilder builder, boolean sign) {
    if (sign) {
      return builder.sign(keyPair);
    }
    return builder.build();
  }

  private int getBrvsAccountQuorum() {
    return irohaAPI.query(Query
        .builder(accountId, 1L)
        .getAccount(accountId)
        .buildSigned(keyPair)
    ).getAccountResponse().getAccount().getQuorum();
  }

  private int getValidQuorumForUserAccount(String accountId) {
    int userQuorum = getUserQuorum(accountId);
    // Since we call it during user registration
    if (userQuorum == UNREACHABLE_QUORUM) {
      userQuorum = 1;
    }
    return (int) (userQuorum + Math.ceil((PROPORTION) * getBrvsAccountQuorum()));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Iterable<String> getRegisteredAccounts() {
    return registeredAccounts;
  }

  private <T> Iterable<T> getAccountsFrom(String accountsHolderAccount,
      Function<Entry, T> processor) {

    Set<T> resultSet = new HashSet<>();
    QueryResponse queryResponse = irohaAPI.query(Query
        .builder(accountId, 1L)
        .getAccount(accountsHolderAccount)
        .buildSigned(keyPair)
    );
    if (!queryResponse.hasAccountResponse()) {
      throw new IllegalStateException(
          "There is no valid response from Iroha about accounts in " + accountsHolderAccount);
    }
    JsonElement rootNode = parser
        .parse(queryResponse
            .getAccountResponse()
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
      logger.warn("Expected hostname-pubkey pair. Got %s : %s", hostname, pubkey);
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

  @Override
  public void addBrvsInstance(BrvsData brvsData) {
    TxStatus txStatus = sendWithLastStatusWaiting(
        Transaction.builder(accountId)
            .addSignatory(accountId, DatatypeConverter.parseHexBinary(brvsData.getHexPubKey()))
            .build()
            .build()
    ).blockingLast().getTxStatus();
    if (!txStatus.equals(TxStatus.COMMITTED)) {
      logger.error("Unable to register %s. Got %s.", brvsData.getHostname(), txStatus.name());
      throw new IllegalStateException(
          "Could not register new BRVS instance. Got wrong status response: " + txStatus.name());
    }
    logger.info("%s registered successfully", brvsData.getHostname());
  }

  private Observable<Endpoint.ToriiResponse> sendWithLastStatusWaiting(
      TransactionOuterClass.Transaction transaction) {
    return irohaAPI.transaction(
        transaction,
        subscriptionStrategy
    );
  }
}
