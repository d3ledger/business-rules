/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.RemoveSignatory;
import iroha.protocol.Commands.SubtractAssetQuantity;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.assets.TransferTxVolumeRule;
import iroha.validation.rules.impl.billing.BillingRule;
import iroha.validation.rules.impl.byacco.ByaccoDomainInternalAssetRule;
import iroha.validation.rules.impl.core.MinimumSignatoriesAmountRule;
import iroha.validation.rules.impl.core.RestrictedKeysRule;
import iroha.validation.rules.impl.core.SampleRule;
import iroha.validation.verdict.Verdict;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.util.Collections;
import java.util.List;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.Utils;
import org.junit.jupiter.api.Test;

class RulesTest {

  private String asset;
  private Transaction transaction;
  private TransferAsset transferAsset;
  private SubtractAssetQuantity subtractAssetQuantity;
  private List<Command> commands;
  private Rule rule;

  private KeyPair keyPair;

  private void init() {
    asset = "asset";
    // transfer mock
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    transferAsset = mock(TransferAsset.class);

    when(transferAsset.getSrcAccountId()).thenReturn("user@users");
    when(transferAsset.getDestAccountId()).thenReturn("destination@users");
    when(transferAsset.getDescription()).thenReturn("description");
    when(transferAsset.getAssetId()).thenReturn(asset);

    final Command command = mock(Command.class);

    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset()).thenReturn(transferAsset);
    when(command.hasRemoveSignatory()).thenReturn(true);

    commands = Collections.singletonList(command);

    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList())
        .thenReturn(commands);
  }

  private void initTransferTxVolumeTest() {
    init();
    rule = new TransferTxVolumeRule(asset, BigDecimal.TEN);
  }

  private void initBillingTest() throws IOException {
    init();
    when(transaction.getPayload().getReducedPayload().getCreatorAccountId())
        .thenReturn("user@users");
    when(transaction.getPayload().getBatch().getReducedHashesCount()).thenReturn(1);
    rule = new BillingRule("http://url",
        "rmqHost",
        1,
        "exchange",
        "key",
        "users",
        "deposit@users",
        "withdrawaleth@users",
        "withdrawalbtc@users"
    ) {
      @Override
      protected void runCacheUpdater() {
      }
    };
  }

  private void initRestrictedKeysRuleTest(boolean bad) {
    init();
    keyPair = new Ed25519Sha3().generateKeypair();
    rule = new RestrictedKeysRule("", Collections.singletonList(keyPair));
    final RemoveSignatory removeSignatory = mock(RemoveSignatory.class);
    final String value = bad ? Utils.toHex(keyPair.getPublic().getEncoded()) : "";
    when(transaction.getPayload().getReducedPayload().getCreatorAccountId())
        .thenReturn("user@users");
    when(removeSignatory.getPublicKey()).thenReturn(value);
    when(commands.get(0).getRemoveSignatory()).thenReturn(removeSignatory);
  }

  private void initSignatoriesAmountTest(boolean bad) {
    init();
    final String fakeAccountId = "id";
    final QueryAPI queryAPI = mock(QueryAPI.class, RETURNS_DEEP_STUBS);
    rule = new MinimumSignatoriesAmountRule("3", queryAPI);
    final RemoveSignatory removeSignatory = mock(RemoveSignatory.class);
    when(removeSignatory.getAccountId()).thenReturn(fakeAccountId);
    final int value = bad ? 3 : 5;
    when(queryAPI.getSignatories(fakeAccountId).getKeysCount()).thenReturn(value);
    when(commands.get(0).getRemoveSignatory()).thenReturn(removeSignatory);
  }

  private void initInternalTransferTest(boolean bad) {
    init();
    rule = new ByaccoDomainInternalAssetRule(asset, bad ? "fakedomain" : "users");
  }

  /**
   * @given {@link SampleRule} instance
   * @when any {@link Transaction} is passed to the rule satisfiability method
   * @then {@link SampleRule} is satisfied by the {@link Transaction}
   */
  @Test
  void sampleRuleTest() {
    rule = new SampleRule();
    // any transaction
    transaction = mock(Transaction.class);
    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link TransferTxVolumeRule} instance with limit of asset amount equal to 10 and for the
   * asset called "asset"
   * @when {@link Transaction} with {@link Command TransferAsset} command of 1 "asset" passed
   * @then {@link TransferTxVolumeRule} is satisfied by such {@link Transaction}
   */
  @Test
  void correctTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.ONE.toPlainString());

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link TransferTxVolumeRule} instance with limit of asset amount equal to 10 and for the
   * asset called "asset"
   * @when {@link Transaction} with {@link Command TransferAsset} command of 100 "otherAsset"
   * passed
   * @then {@link TransferTxVolumeRule} is satisfied by such {@link Transaction}
   */
  @Test
  void otherAssetTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn("otherAsset");
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link TransferTxVolumeRule} instance with limit of asset amount equal to 10 and for the
   * asset called "asset"
   * @when {@link Transaction} with {@link Command TransferAsset} command of 100 "asset" passed
   * @then {@link TransferTxVolumeRule} is NOT satisfied by such {@link Transaction}
   */
  @Test
  void violatedTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link BillingRule} instance with no billing data
   * @when {@link Transaction} with the only {@link Command TransferAsset} command of 100 "asset"
   * passed
   * @then {@link BillingRule} is satisfied by such {@link Transaction}
   */
  @Test
  void emptyBillingRuleGoodTest() throws IOException {
    initBillingTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link BillingRule} instance with no billing data
   * @when {@link Transaction} with the only {@link Command TransferAsset} command to destination of
   * a billing account
   * @then {@link BillingRule} is satisfied by such {@link Transaction}
   */
  @Test
  void emptyBillingRuleBadTest() throws IOException {
    initBillingTest();

    subtractAssetQuantity = mock(SubtractAssetQuantity.class);
    when(subtractAssetQuantity.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());
    when(subtractAssetQuantity.getAssetId()).thenReturn(asset);
    final Command command = mock(Command.class);

    when(command.hasSubtractAssetQuantity()).thenReturn(true);
    when(command.getSubtractAssetQuantity()).thenReturn(subtractAssetQuantity);

    commands = Collections.singletonList(command);

    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList())
        .thenReturn(commands);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link RestrictedKeysRule} instance with key specified
   * @when {@link Transaction} with {@link Command RemoveSignatory} command of the same key
   * @then {@link RestrictedKeysRule} is not satisfied by such {@link Transaction}
   */
  @Test
  void restrictedRuleTest() {
    initRestrictedKeysRuleTest(true);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link RestrictedKeysRule} instance with key specified
   * @when {@link Transaction} with {@link Command RemoveSignatory} command of other key
   * @then {@link RestrictedKeysRule} is satisfied by such {@link Transaction}
   */
  @Test
  void restrictedGoodRuleTest() {
    initRestrictedKeysRuleTest(false);

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link MinimumSignatoriesAmountRule} instance with 3 amount
   * @when {@link Transaction} with {@link Command RemoveSignatory} command from account having only
   * 3 signatories appears
   * @then {@link MinimumSignatoriesAmountRule} is not satisfied by such {@link Transaction}
   */
  @Test
  void minimumSignatoriesAmountRuleTest() {
    initSignatoriesAmountTest(true);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link MinimumSignatoriesAmountRule} instance with 3 amount
   * @when {@link Transaction} with {@link Command RemoveSignatory} command from account having 5
   * signatories appears
   * @then {@link MinimumSignatoriesAmountRule} is satisfied by such {@link Transaction}
   */
  @Test
  void minimumSignatoriesAmountGoodRuleTest() {
    initSignatoriesAmountTest(false);

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link ByaccoDomainInternalAssetRule} instance with configured domain and asset id
   * @when {@link Transaction} with {@link Command TransferAsset} command with the asset and a valid
   * domain appears
   * @then {@link ByaccoDomainInternalAssetRule} is satisfied by such {@link Transaction}
   */
  @Test
  void interDomainAssetGoodRuleTest() {
    initSignatoriesAmountTest(false);

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link ByaccoDomainInternalAssetRule} instance with configured domain and asset id
   * @when {@link Transaction} with {@link Command TransferAsset} command with the asset and an
   * invalid domain appears
   * @then {@link ByaccoDomainInternalAssetRule} is not satisfied by such {@link Transaction}
   */
  @Test
  void interDomainAssetGoodBadTest() {
    initSignatoriesAmountTest(true);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }
}
