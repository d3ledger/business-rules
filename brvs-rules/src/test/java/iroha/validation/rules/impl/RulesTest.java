/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl;

import static iroha.validation.rules.impl.billing.BillingRule.FIAT_DOMAIN;
import static iroha.validation.rules.impl.billing.BillingRule.XOR_ASSET_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.RemoveSignatory;
import iroha.protocol.Commands.SubtractAssetQuantity;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.assets.TransferTxVolumeRule;
import iroha.validation.rules.impl.billing.BillingInfo;
import iroha.validation.rules.impl.billing.BillingInfo.BillingTypeEnum;
import iroha.validation.rules.impl.billing.BillingInfo.FeeTypeEnum;
import iroha.validation.rules.impl.billing.BillingRule;
import iroha.validation.rules.impl.core.MinimumSignatoriesAmountRule;
import iroha.validation.rules.impl.core.RestrictedKeysRule;
import iroha.validation.rules.impl.core.SampleRule;
import iroha.validation.verdict.Verdict;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.util.ArrayList;
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
    init("asset#domain", "destination@users");
  }

  private void init(String assetToSave, String destAccountId) {
    asset = assetToSave;
    // transfer mock
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    transferAsset = mock(TransferAsset.class);

    when(transferAsset.getSrcAccountId()).thenReturn("user@users");
    when(transferAsset.getDestAccountId()).thenReturn(destAccountId);
    when(transferAsset.getDescription()).thenReturn("description");
    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn("100");

    final Command command = mock(Command.class);

    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset()).thenReturn(transferAsset);
    when(command.hasRemoveSignatory()).thenReturn(true);

    commands = new ArrayList<>();
    commands.add(command);

    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList())
        .thenReturn(commands);
    when(transaction.getPayload().getReducedPayload().getCreatorAccountId())
        .thenReturn("user@users");
  }

  private void initTransferTxVolumeTest() {
    init();
    rule = new TransferTxVolumeRule(asset, BigDecimal.TEN);
  }

  private void initJustBillingTest(boolean withFee) throws IOException {
    if (withFee) {
      subtractAssetQuantity = mock(SubtractAssetQuantity.class);
      when(subtractAssetQuantity.getAmount()).thenReturn("0.1");
      when(subtractAssetQuantity.getAssetId()).thenReturn(asset);
      final Command command = mock(Command.class);

      when(command.hasSubtractAssetQuantity()).thenReturn(true);
      when(command.getSubtractAssetQuantity()).thenReturn(subtractAssetQuantity);

      commands.add(command);

      when(transaction
          .getPayload()
          .getReducedPayload()
          .getCommandsList())
          .thenReturn(commands);
    }

    rule = new BillingRule("http://url",
        "rmqHost",
        1,
        "exchange",
        "key",
        "users",
        "deposit@users",
        "withdrawaleth@users",
        "withdrawalbtc@users",
        "exchanger@notary"
    ) {
      @Override
      protected void runCacheUpdater() {
      }
    };
  }

  private void initBillingTest(boolean withFee, String assetToSave, String destAccountId)
      throws IOException {
    init(assetToSave, destAccountId);
    initJustBillingTest(withFee);
  }

  private void initBillingTest(boolean withFee) throws IOException {
    init();
    initJustBillingTest(withFee);
  }

  private void initBillingExchangerTest(boolean withFee) throws IOException {
    initBillingExchangerTest(withFee, XOR_ASSET_ID);
  }

  private void initBillingExchangerTest(boolean withFee, String assetToSave) throws IOException {
    initBillingTest(withFee, assetToSave, "exchanger@notary");

    final BillingRule billingRule = spy(new BillingRule("http://url",
        "rmqHost",
        1,
        "exchange",
        "key",
        "users",
        "deposit@users",
        "withdrawaleth@users",
        "withdrawalbtc@users",
        "exchanger@notary"
    ) {
      @Override
      protected void runCacheUpdater() {
      }
    });
    when(billingRule.getBillingInfoFor(any(), eq(XOR_ASSET_ID), eq(BillingTypeEnum.EXCHANGE)))
        .thenReturn(
            new BillingInfo(
                "users",
                BillingTypeEnum.EXCHANGE,
                XOR_ASSET_ID,
                FeeTypeEnum.FIXED,
                new BigDecimal("0.1"),
                0
            )
        );
    rule = billingRule;
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
    initBillingTest(false);

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
    initBillingTest(true);

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
   * @given {@link BillingRule} instance with billing data
   * @when {@link Transaction} with the proper {@link Command TransferAsset} command of 100
   * "xor#sora" passed and {@link Command SubtractAssetQty} fee
   * @then {@link BillingRule} is satisfied by such {@link Transaction}
   */
  @Test
  void correctExchangerGoodRuleTest() throws IOException {
    initBillingExchangerTest(true);

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link BillingRule} instance with billing data
   * @when {@link Transaction} with the proper {@link Command TransferAsset} command of 100
   * "xor#sora" passed but no {@link Command SubtractAssetQty} fee set
   * @then {@link BillingRule} is rejected by such {@link Transaction}
   */
  @Test
  void incorrectExchangerGoodRuleTest() throws IOException {
    initBillingExchangerTest(false);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link BillingRule} instance with billing data
   * @when {@link Transaction} with the proper {@link Command TransferAsset} command of 100 a fiat
   * passed and {@link Command SubtractAssetQty} paid in the same asset
   * @then {@link BillingRule} is rejected by such {@link Transaction}
   */
  @Test
  void incorrectAssetFeeExchangerRuleTest() throws IOException {
    initBillingExchangerTest(true, "asset#" + FIAT_DOMAIN);

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }
}
