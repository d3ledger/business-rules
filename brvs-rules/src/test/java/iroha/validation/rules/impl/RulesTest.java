package iroha.validation.rules.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.billing.BillingRule;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.api.support.membermodification.MemberMatcher;

class RulesTest {

  private String asset;
  private Transaction transaction;
  private TransferAsset transferAsset;
  private List<Command> commands;
  private Rule rule;

  private void init() {
    asset = "asset";
    // transfer mock
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    final Command command = mock(Command.class);
    commands = Collections.singletonList(command);
    transferAsset = mock(TransferAsset.class);

    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList())
        .thenReturn(commands);

    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset()).thenReturn(transferAsset);
    when(transferAsset.getSrcAccountId()).thenReturn("user@users");
    when(transferAsset.getDestAccountId()).thenReturn("transfer_billing@users");
  }

  private void initTransferTxVolumeTest() {
    init();
    rule = new TransferTxVolumeRule(asset, BigDecimal.TEN);
  }

  private void initBillingTest() {
    init();
    PowerMockito.suppress(MemberMatcher
        .methods(BillingRule.class,
            "runCacheUpdater",
            "readBillingOnStartup",
            "executeGetRequest")
    );
    rule = PowerMockito.mock(BillingRule.class);
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
    assertTrue(rule.isSatisfiedBy(transaction));
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

    assertTrue(rule.isSatisfiedBy(transaction));
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

    assertTrue(rule.isSatisfiedBy(transaction));
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

    assertFalse(rule.isSatisfiedBy(transaction));
  }

  /**
   * @given {@link BillingRule} instance with no billing data
   * @when {@link Transaction} with {@link Command TransferAsset} command of 100 "asset" passed
   * @then {@link BillingRule} is satisfied by such {@link Transaction}
   */
  @Test
  void emptyBillingRuleTest() {
    initBillingTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertFalse(rule.isSatisfiedBy(transaction));
  }
}
