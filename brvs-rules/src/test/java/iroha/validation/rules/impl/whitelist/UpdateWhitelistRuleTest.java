/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands;
import iroha.protocol.Commands.Command;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.protocol.Queries.Query;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.Verdict;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

class UpdateWhitelistRuleTest {

  private static final Ed25519Sha3 crypto = new Ed25519Sha3();

  private final String brvsAccountId = "brvs@brvs";
  private final KeyPair brvsAccountKeyPair = crypto.generateKeypair();
  private final IrohaAPI irohaAPI = mock(IrohaAPI.class);
  private final long validationPeriod = 10;

  private String clientId = "client@d3";

  private final Commands.Command command = mock(Commands.Command.class, RETURNS_DEEP_STUBS);
  private final Transaction transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
  private final QueryResponse queryResponse = mock(QueryResponse.class, RETURNS_DEEP_STUBS);

  @Captor
  private final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);

  private final Rule rule = new UpdateWhitelistRule(
      new QueryAPI(irohaAPI, brvsAccountId, brvsAccountKeyPair),
      validationPeriod);

  private void setEnvironmentTest(List<String> whitelist, String key, String clientListResponse) {
    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList())
        .thenReturn(Collections.singletonList(command));
    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCreatorAccountId())
        .thenReturn(clientId);

    when(command.hasSetAccountDetail()).thenReturn(true);
    when(command.getSetAccountDetail().getAccountId()).thenReturn(clientId);
    when(command.getSetAccountDetail().getKey()).thenReturn(key);
    String jsonEthWhitelist = WhitelistUtils.serializeClientWhitelist(whitelist);
    when(command.getSetAccountDetail().getValue()).thenReturn(jsonEthWhitelist);

    when(irohaAPI.query(isA(Query.class))).thenReturn(queryResponse);
    when(queryResponse.hasAccountDetailResponse()).thenReturn(true);
    when(queryResponse.getAccountDetailResponse().getDetail()).thenReturn(clientListResponse);
  }

  /**
   * Correct execution of validation of Ethereum whitelist setting
   *
   * @given {@link UpdateWhitelistRule} instance
   * @when {@link Transaction} passed to the isSatisfiedBy
   * @then {@link UpdateWhitelistRule} validates the {@link Transaction}
   */
  @Test
  void sunnyDayTest() {
    String key = WhitelistUtils.ETH_WHITELIST_KEY;

    List<String> ethWhitelist = new ArrayList<>();
    ethWhitelist.add("0x6826d84158e516f631bBf14586a9BE7e255b2D20");
    ethWhitelist.add("0x6826d84158e516f631bBf14586a9BE7e255b2D23");

    // BRVS response with empty whitelist
    String clientListResponse = "{}";

    setEnvironmentTest(ethWhitelist, key, clientListResponse);

    doNothing().when(irohaAPI).transactionSync(isA(Transaction.class));

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());

    verify(irohaAPI).transactionSync(captor.capture());

    assertEquals(brvsAccountId,
        captor.getValue().getPayload().getReducedPayload().getCreatorAccountId());
    assertEquals(1, captor.getValue().getPayload().getReducedPayload().getCommandsList().size());
    Command cmd = captor.getValue().getPayload().getReducedPayload().getCommandsList().get(0);
    assertTrue(cmd.hasSetAccountDetail());
    assertEquals(clientId, cmd.getSetAccountDetail().getAccountId());
    assertEquals(key, cmd.getSetAccountDetail().getKey());

    Set<String> whitelistSet = new HashSet<>(ethWhitelist);
    assertEquals(
        WhitelistUtils.deserializeBRVSWhitelist(cmd.getSetAccountDetail().getValue()).keySet(),
        whitelistSet);
  }

  /**
   * Correct execution of validation of Ethereum whitelist setting
   *
   * @given {@link UpdateWhitelistRule} instance and brvs has one address
   * @when {@link Transaction} passed to the isSatisfiedBy
   * @then {@link UpdateWhitelistRule} validates the {@link Transaction}
   */
  @Test
  void appendTest() {
    String key = WhitelistUtils.ETH_WHITELIST_KEY;

    List<String> ethWhitelist = new ArrayList<>();
    ethWhitelist.add("0x6826d84158e516f631bBf14586a9BE7e255b2D20");
    ethWhitelist.add("0x6826d84158e516f631bBf14586a9BE7e255b2D23");

    // BRVS response with whitelist
    String clientListResponse = "{\"brvs@brvs\" : {\"eth_whitelist\" : \"{\\\"0x6826d84158e516f631bBf14586a9BE7e255b2D20\\\":1555063373}\"}}";

    setEnvironmentTest(ethWhitelist, key, clientListResponse);

    doNothing().when(irohaAPI).transactionSync(isA(Transaction.class));

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());

    verify(irohaAPI).transactionSync(captor.capture());

    assertEquals(brvsAccountId,
        captor.getValue().getPayload().getReducedPayload().getCreatorAccountId());
    assertEquals(1, captor.getValue().getPayload().getReducedPayload().getCommandsList().size());
    Command cmd = captor.getValue().getPayload().getReducedPayload().getCommandsList().get(0);
    assertTrue(cmd.hasSetAccountDetail());
    assertEquals(clientId, cmd.getSetAccountDetail().getAccountId());
    assertEquals(key, cmd.getSetAccountDetail().getKey());

    Set<String> whitelistSet = new HashSet<>(ethWhitelist);
    assertEquals(
        WhitelistUtils.deserializeBRVSWhitelist(cmd.getSetAccountDetail().getValue()).keySet(),
        whitelistSet);
  }
}
