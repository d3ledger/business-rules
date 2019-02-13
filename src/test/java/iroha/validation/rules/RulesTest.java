package iroha.validation.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.rules.impl.TransferTxVolumeRule;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class RulesTest {

  private String asset;
  private Transaction transaction;
  private TransferAsset transferAsset;
  private Rule rule;

  private void initTransferTxVolumeTest() {
    asset = "asset";
    rule = new TransferTxVolumeRule(asset, BigDecimal.TEN);
    // transfer mock
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    Command command = mock(Command.class);
    transferAsset = mock(TransferAsset.class);

    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
    )
        .thenReturn(Collections.singletonList(command));

    when(command
        .hasTransferAsset()
    )
        .thenReturn(true);

    when(command
        .getTransferAsset()
    )
        .thenReturn(transferAsset);
  }

  @Test
  void sampleRuleTest() {
    rule = new SampleRule();
    // any transaction
    transaction = mock(Transaction.class);
    assertTrue(rule.isSatisfiedBy(transaction));
  }

  @Test
  void correctTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.ONE.toPlainString());

    assertTrue(rule.isSatisfiedBy(transaction));
  }

  @Test
  void otherAssetTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn("otherAsset");
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertTrue(rule.isSatisfiedBy(transaction));
  }

  @Test
  void violatedTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertFalse(rule.isSatisfiedBy(transaction));
  }
}
