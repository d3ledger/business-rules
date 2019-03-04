package iroha.validation.transactions.provider.impl.util;

import iroha.protocol.QryResponses.QueryResponse;
import java.security.KeyPair;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserQuorumProvider {

  private static final Logger logger = LoggerFactory.getLogger(UserQuorumProvider.class);
  // Not to let BRVS to take it in processing
  // Max quorum is 128
  private static final int UNREACHABLE_QUORUM = 129;

  private final String accountId;
  private final KeyPair keyPair;
  private final IrohaAPI irohaAPI;
  private final String writerAccount;
  private final String userQuorumAttribute;

  public UserQuorumProvider(String accountId,
      KeyPair keyPair,
      IrohaAPI irohaAPI,
      String writerAccount,
      String userQuorumAttribute) {
    this.accountId = accountId;
    this.keyPair = keyPair;
    this.irohaAPI = irohaAPI;
    this.writerAccount = writerAccount;
    this.userQuorumAttribute = userQuorumAttribute;
  }

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
}
