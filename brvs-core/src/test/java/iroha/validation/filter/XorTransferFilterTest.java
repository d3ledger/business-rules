/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.filter;

import static iroha.validation.rules.impl.billing.BillingRule.XOR_ASSET_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.transactions.filter.sora.XorTransfersTemporaryIgnoringFilter;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class XorTransferFilterTest {

  private static final String DOMAIN = "sora";
  private static final String USERNAME = "user";
  private static final String USER_ID = USERNAME + "@" + DOMAIN;
  private TransactionBatch transactionBatch;
  private Transaction transaction;
  private XorTransfersTemporaryIgnoringFilter xorTransfersTemporaryIgnoringFilter = new XorTransfersTemporaryIgnoringFilter();

  @BeforeEach
  public void initMocks() {
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    when(transaction.getPayload().getReducedPayload().getCreatorAccountId()).thenReturn(USER_ID);
    final Command command = mock(Command.class);
    final TransferAsset transferAsset = mock(TransferAsset.class);
    when(transferAsset.getAssetId()).thenReturn(XOR_ASSET_ID);
    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset()).thenReturn(transferAsset);
    when(transaction.getPayload().getReducedPayload().getCommandsList())
        .thenReturn(Collections.singletonList(command));
    transactionBatch = new TransactionBatch(Collections.singletonList(transaction));
  }

  /**
   * @given {@link XorTransfersTemporaryIgnoringFilter} instance enabled
   * @when a user tries to perform a transaction with asset of XOR
   * @then {@link XorTransfersTemporaryIgnoringFilter} filters the transaction out
   */
  @Test
  public void enabledTest() {
    xorTransfersTemporaryIgnoringFilter.enable();

    assertFalse(xorTransfersTemporaryIgnoringFilter.filter(transactionBatch));
  }

  /**
   * @given {@link XorTransfersTemporaryIgnoringFilter} instance disabled
   * @when a user tries to perform a transaction with asset of XOR
   * @then {@link XorTransfersTemporaryIgnoringFilter} filters the transaction successfully
   */
  @Test
  public void disabledTest() {
    xorTransfersTemporaryIgnoringFilter.disable();

    assertTrue(xorTransfersTemporaryIgnoringFilter.filter(transactionBatch));
  }
}
