package iroha.validation.rest;

import java.security.KeyPair;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;

public class AccountValidityChecker {

  private final String accountId;
  private final KeyPair keyPair;
  private final IrohaAPI irohaAPI;

  public AccountValidityChecker(String accountId,
      KeyPair keyPair,
      IrohaAPI irohaAPI) {
    this.accountId = accountId;
    this.keyPair = keyPair;
    this.irohaAPI = irohaAPI;
  }

  public boolean existsInIroha(String userAccountId) {
    return irohaAPI
        .query(
            Query.builder(accountId, 1L)
                .getAccount(userAccountId)
                .buildSigned(keyPair)
        )
        .getAccountResponse()
        .hasAccount();
  }
}
