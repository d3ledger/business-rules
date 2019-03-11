package iroha.validation.rules.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import iroha.protocol.Commands.Command;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import java.security.KeyPair;
import java.util.HashSet;
import java.util.Set;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewBrvsRule implements Rule {

  private static final Logger logger = LoggerFactory.getLogger(NewBrvsRule.class);
  private static final JsonParser parser = new JsonParser();

  private String brvsAccountId;
  private KeyPair keyPair;
  private IrohaAPI irohaAPI;

  public NewBrvsRule(String brvsAccountId,
      KeyPair keyPair,
      IrohaAPI irohaAPI) {
    this.brvsAccountId = brvsAccountId;
    this.keyPair = keyPair;
    this.irohaAPI = irohaAPI;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSatisfiedBy(Transaction transaction) {
    try {
      Set<String> brvsPubKeys = getPubKeys();
      return transaction
          .getPayload()
          .getReducedPayload()
          .getCommandsList()
          .stream()
          .filter(Command::hasAddSignatory)
          .filter(command -> command.getAddSignatory().getAccountId().equals(brvsAccountId))
          .map(command -> command.getAddSignatory().getPublicKey())
          .allMatch(brvsPubKeys::contains);
    } catch (Exception e) {
      logger.error("Couldn't read brvs keys from Iroha", e);
      return false;
    }
  }

  private Set<String> getPubKeys() throws Exception {
    Set<String> resultSet = new HashSet<>();
    QueryResponse queryResponse = irohaAPI.query(Query
        .builder(brvsAccountId, 1L)
        .getAccount(brvsAccountId)
        .buildSigned(keyPair)
    );
    if (!queryResponse.hasAccountResponse()) {
      throw new Exception(
          "There is no valid response from Iroha about accounts in " + brvsAccountId);
    }
    JsonElement rootNode = parser
        .parse(queryResponse
            .getAccountResponse()
            .getAccount()
            .getJsonData()
        );
    ((JsonObject) rootNode).entrySet().forEach(accountSetter ->
        accountSetter
            .getValue()
            .getAsJsonObject()
            .entrySet()
            .forEach(entry -> {
                  resultSet.add(entry.getKey());
                }
            )
    );
    return resultSet;
  }
}
