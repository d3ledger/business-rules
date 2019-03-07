package iroha.validation.transactions.provider.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.UserQuorumProvider;
import java.security.KeyPair;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AccountManager implements UserQuorumProvider, RegistrationProvider {

  private static final Logger logger = LoggerFactory.getLogger(AccountManager.class);
  // Not to let BRVS to take it in processing
  // Max quorum is 128
  private static final int UNREACHABLE_QUORUM = 129;
  private static final Pattern accounIdPattern = Pattern.compile("[a-z0-9_]{1,32}@[a-z0-9]+");
  private static final JsonParser parser = new JsonParser();
  private final Set<String> registeredAccounts = new HashSet<>();

  private final String accountId;
  private final KeyPair keyPair;
  private final IrohaAPI irohaAPI;
  private final String writerAccount;
  private final String userQuorumAttribute;
  private final String accountsHolderAccount;

  public AccountManager(String accountId,
      KeyPair keyPair,
      IrohaAPI irohaAPI,
      String writerAccount,
      String userQuorumAttribute,
      String accountsHolderAccount) {
    this.accountId = accountId;
    this.keyPair = keyPair;
    this.irohaAPI = irohaAPI;
    this.writerAccount = writerAccount;
    this.userQuorumAttribute = userQuorumAttribute;
    this.accountsHolderAccount = accountsHolderAccount;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getUserQuorum(String targetAccount) {
    QueryResponse queryResponse = irohaAPI.query(Query.builder(accountId, 1L)
        .getAccountDetail(targetAccount, writerAccount, userQuorumAttribute)
        .buildSigned(keyPair));
    if (!queryResponse.hasAccountDetailResponse()) {
      logger.error("Account detail is not set for account: %s", targetAccount);
      return UNREACHABLE_QUORUM;
    }
    try {
      return Integer.parseInt(queryResponse.getAccountDetailResponse().getDetail().split("\"")[5]);
    } catch (NumberFormatException e) {
      logger.error("Error occurred parsing quorum details for " + targetAccount, e);
      return UNREACHABLE_QUORUM;
    } catch (Exception e) {
      logger.error("Unknown exception occurred retrieving quorum data", e);
      return UNREACHABLE_QUORUM;
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
    return accounIdPattern.matcher(accountId).matches();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized void register(String accountId) {
    if (!hasValidFormat(accountId)) {
      throw new IllegalArgumentException("Invalid account format. Use 'username@domain'.");
    }
    if (!existsInIroha(accountId)) {
      throw new IllegalArgumentException("Account does not exist.");
    }
    // TODO ADD SIGNATORY WHICH IS HARD
    registeredAccounts.add(accountId);
    logger.info("Successfully registered " + accountId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Iterable<String> getRegisteredAccounts() {
    return registeredAccounts;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterable<String> getUserAccounts() {
    Set<String> users = new HashSet<>();
    QueryResponse queryResponse = irohaAPI.query(Query
        .builder(accountId, 1L)
        .getAccount(accountsHolderAccount)
        .buildSigned(keyPair)
    );
    if (!queryResponse.hasAccountResponse()) {
      throw new IllegalStateException(
          "There is no valid response from Iroha about user accounts in " + accountsHolderAccount);
    }
    JsonElement rootNode = parser
        .parse(queryResponse
            .getAccountResponse()
            .getAccount()
            .getJsonData()
        );
    ((JsonObject) rootNode).entrySet().forEach(outerEntry ->
        outerEntry
            .getValue()
            .getAsJsonObject()
            .entrySet()
            .forEach(entry -> {
                  String key = entry.getKey();
                  String suffix = entry.getValue().toString();
                  if (key.endsWith(suffix)) {
                    // since accounts stored as key-value pairs of
                    // usernamedomain -> domain
                    // we need to extract username from the key and add the domain to it separated with @
                    users.add(key.substring(0, key.lastIndexOf(suffix)).concat("@").concat(suffix));
                  }
                }

            )
    );
    return users;
  }
}
