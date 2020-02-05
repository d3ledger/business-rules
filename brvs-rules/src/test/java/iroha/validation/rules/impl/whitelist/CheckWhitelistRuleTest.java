/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.protocol.Queries.Query;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.Verdict;
import java.security.KeyPair;
import java.util.Collections;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import org.junit.jupiter.api.Test;

class CheckWhitelistRuleTest {

  private static final Ed25519Sha3 crypto = new Ed25519Sha3();

  private final String brvsAccountId = "brvs@brvs";
  private final KeyPair brvsAccountKeyPair = crypto.generateKeypair();
  private final IrohaAPI irohaAPI = mock(IrohaAPI.class);

  private final String clientId = "client@d3";
  private final String amount = "123";
  private final String witdrawalAddress = "0x6826d84158e516f631bBf14586a9BE7e255b2D20";
  private final String assetId = "ether#ethereum";
  private final String withdrawalClientId = "notary@notary";

  private final Commands.Command command = mock(Commands.Command.class, RETURNS_DEEP_STUBS);
  private final Transaction transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
  private final QueryResponse queryResponse = mock(QueryResponse.class, RETURNS_DEEP_STUBS);

  private Rule rule = new CheckWhitelistRule(
      new QueryAPI(irohaAPI, brvsAccountId, brvsAccountKeyPair), withdrawalClientId);

  private void setEnvironmentTest(String clientListResponse) {
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
    // BRVS response with whitelist
    String clientListResponse = "{\"brvs@brvs\" : {\"eth_whitelist\" : \"{\\\"0x6826d84158e516f631bBf14586a9BE7e255b2D20\\\":1}\"}}";

    setEnvironmentTest(clientListResponse);

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
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
    // BRVS response with whitelist with different address
    String clientListResponse = "{\"brvs@brvs\" : {\"eth_whitelist\" : \"{\\\"0x6826d84158e516f631bBf14586a9BE7e255b2D22\\\":1}\"}}";

    setEnvironmentTest(clientListResponse);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
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
    // BRVS response with whitelist with different address
    String clientListResponse = "{\"brvs@brvs\" : {\"eth_whitelist\" : \"{\\\"0x6826d84158e516f631bBf14586a9BE7e255b2D22\\\":999999999999}\"}}";

    setEnvironmentTest(clientListResponse);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }
}
