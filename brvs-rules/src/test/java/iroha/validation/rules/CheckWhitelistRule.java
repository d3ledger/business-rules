package iroha.validation.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.protocol.Queries.Query;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.impl.whitelist.WhitelistUtils;
import java.security.KeyPair;
import java.util.Collections;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

class CheckWhitelistRule {

  private static final Ed25519Sha3 crypto = new Ed25519Sha3();

  private String brvsAccountId = "brvs@brvs";
  private KeyPair brvsAccountKeyPair = crypto.generateKeypair();
  private IrohaAPI irohaAPI = mock(IrohaAPI.class);
  private long validationPeriod = 10;

  private String clientId = "client@d3";
  private String amount = "123";
  private String witdrawalAddress = "0x6826d84158e516f631bBf14586a9BE7e255b2D20";
  private String assetId = "ether#ethereum";
  private String withdrawalClientId = "notary@notary";

  private Commands.Command command = mock(Commands.Command.class, RETURNS_DEEP_STUBS);
  private Transaction transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
  private QueryResponse queryResponse = mock(QueryResponse.class, RETURNS_DEEP_STUBS);

  private Rule rule = new iroha.validation.rules.impl.whitelist.CheckWhitelistRule(brvsAccountId,
      brvsAccountKeyPair, irohaAPI);

  private void setEnvironmentTest(String key, String clientListResponse) {
    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList())
        .thenReturn(Collections.singletonList(command));

    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset().getAmount()).thenReturn(amount);
    when(command.getTransferAsset().getSrcAccountId()).thenReturn(clientId);
    when(command.getTransferAsset().getDescription()).thenReturn(witdrawalAddress);
    when(command.getTransferAsset().getAssetId()).thenReturn(assetId);
    when(command.getTransferAsset().getDestAccountId()).thenReturn(withdrawalClientId);

    when(irohaAPI.query(isA(Query.class))).thenReturn(queryResponse);
    when(queryResponse.hasAccountDetailResponse()).thenReturn(true);
    when(queryResponse.getAccountDetailResponse().getDetail()).thenReturn(clientListResponse);
  }

  /**
   * Correct execution of validation of Ethereum withdrawal
   *
   * @given {@link CheckWhitelistRule} instance and address is whitelisted
   * @when {@link Transaction} passed to the isSatisfiedBy
   * @then {@link CheckWhitelistRule} validates the {@link Transaction}
   */
  @Test
  void withdrawalTest() {
    String key = WhitelistUtils.ETH_WHITELIST_KEY;

    // BRVS response with whitelist
    String clientListResponse = "{\"brvs@brvs\" : {\"eth_whitelist\" : \"{\\\"0x6826d84158e516f631bBf14586a9BE7e255b2D20\\\":1}\"}}";

    setEnvironmentTest(key, clientListResponse);

    assertTrue(rule.isSatisfiedBy(transaction));
  }

  /**
   * Correct execution of validation of Ethereum withdrawal
   *
   * @given {@link CheckWhitelistRule} instance and address is whitelisted
   * @when {@link Transaction} passed to the isSatisfiedBy
   * @then {@link CheckWhitelistRule} validates the {@link Transaction}
   */
  @Test
  void withdrawalNotWhitelistedTest() {
    String key = WhitelistUtils.ETH_WHITELIST_KEY;

    // BRVS response with whitelist with different address
    String clientListResponse = "{\"brvs@brvs\" : {\"eth_whitelist\" : \"{\\\"0x6826d84158e516f631bBf14586a9BE7e255b2D22\\\":1}\"}}";

    setEnvironmentTest(key, clientListResponse);

    assertFalse(rule.isSatisfiedBy(transaction));
  }

  /**
   * Correct execution of validation of Ethereum withdrawal
   *
   * @given {@link CheckWhitelistRule} instance and address is not validated yet
   * @when {@link Transaction} passed to the isSatisfiedBy
   * @then {@link CheckWhitelistRule} validates the {@link Transaction}
   */
  @Test
  void withdrawalNotValidatedTest() {
    String key = WhitelistUtils.ETH_WHITELIST_KEY;

    // BRVS response with whitelist with different address
    String clientListResponse = "{\"brvs@brvs\" : {\"eth_whitelist\" : \"{\\\"0x6826d84158e516f631bBf14586a9BE7e255b2D22\\\":999999999999}\"}}";

    setEnvironmentTest(key, clientListResponse);

    assertFalse(rule.isSatisfiedBy(transaction));
  }

}
