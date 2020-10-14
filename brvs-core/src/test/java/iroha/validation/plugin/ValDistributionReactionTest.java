/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.plugin;

import static iroha.validation.transactions.plugin.impl.sora.ValDistributionPluggableLogic.VAL_ASSET_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper;
import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.Endpoint.ToriiResponse;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.core.provider.RegisteredUsersStorage;
import iroha.validation.transactions.plugin.impl.sora.ValDistributionPluggableLogic;
import iroha.validation.utils.ValidationUtils;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class ValDistributionReactionTest {

  private static final String BRVS_ID = "sora@brvs";
  private static final String SUPERUSER_ID = "superuser@bootstrap";
  private static final String USER_ID = "user@sora";
  private static final String TOTAL_AMOUNT = "100";
  private Block block;
  private Transaction transaction;
  private ValDistributionPluggableLogic valDistributionPluggableLogic;
  private ArgumentCaptor<Transaction> transactionArgumentCaptor;

  public void initMocks(String userAmount) {
    block = mock(Block.class, RETURNS_DEEP_STUBS);
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    when(transaction.getPayload().getReducedPayload().getCreatorAccountId())
        .thenReturn(SUPERUSER_ID);
    final Command command = mock(Command.class);
    final TransferAsset transferAsset = mock(TransferAsset.class);
    when(transferAsset.getSrcAccountId()).thenReturn(SUPERUSER_ID);
    when(transferAsset.getDestAccountId()).thenReturn(BRVS_ID);
    when(transferAsset.getAmount()).thenReturn(TOTAL_AMOUNT);
    when(transferAsset.getAssetId()).thenReturn(VAL_ASSET_ID);
    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset()).thenReturn(transferAsset);
    when(transaction.getPayload().getReducedPayload().getCommandsList())
        .thenReturn(Collections.singletonList(command));
    when(block.getBlockV1().getPayload().getTransactionsList())
        .thenReturn(Collections.singletonList(transaction));

    final IrohaAPI irohaAPI = mock(IrohaAPI.class, RETURNS_DEEP_STUBS);
    transactionArgumentCaptor = ArgumentCaptor.forClass(Transaction.class);
    final ToriiResponse response = mock(ToriiResponse.class, RETURNS_DEEP_STUBS);
    when(response.getTxStatus()).thenReturn(TxStatus.COMMITTED);
    when(irohaAPI.transaction(transactionArgumentCaptor.capture(), any()))
        .thenReturn(Observable.just(response));
    final IrohaQueryHelper irohaQueryHelper = mock(IrohaQueryHelper.class, RETURNS_DEEP_STUBS);
    when(irohaQueryHelper.getAccountAsset(eq(USER_ID), any()).get()).thenReturn(userAmount);
    when(irohaQueryHelper.getAccountAsset(eq(BRVS_ID), any()).get()).thenReturn(TOTAL_AMOUNT);
    valDistributionPluggableLogic = new ValDistributionPluggableLogic(
        new QueryAPI(
            irohaAPI,
            BRVS_ID,
            ValidationUtils.generateKeypair()
        ),
        SUPERUSER_ID,
        irohaQueryHelper,
        new RegisteredUsersStorage() {

          @Override
          public void add(String accountId) {

          }

          @Override
          public boolean contains(String accountId) {
            return false;
          }

          @Override
          public <T> Set<T> process(Function<Iterable<String>, Collection<T>> method) {
            return new HashSet<>(method.apply(Collections.singleton(USER_ID)));
          }
        },
        TOTAL_AMOUNT
    );
  }

  /**
   * @given {@link ValDistributionPluggableLogic} instance
   * @when VAL tokens transfer to brvs happens
   * @then {@link ValDistributionPluggableLogic} filters needed command and performs the
   * distribution of VALs to user accounts from {@link RegisteredUsersStorage} with burning of
   * remaining volume
   */
  @SneakyThrows
  @Test
  public void sunnyDayTestWithBurning() {
    final String userAmount = "50";
    initMocks(userAmount);
    valDistributionPluggableLogic.apply(block);
    final Transaction value = transactionArgumentCaptor.getValue();

    assertEquals(2, value.getPayload().getReducedPayload().getCommandsCount());
    final Command actualTransferCommand = value.getPayload().getReducedPayload().getCommands(0);
    final Command actualSubtractCommand = value.getPayload().getReducedPayload().getCommands(1);
    assertTrue(actualTransferCommand.hasTransferAsset());
    assertEquals(BRVS_ID, actualTransferCommand.getTransferAsset().getSrcAccountId());
    assertEquals(USER_ID, actualTransferCommand.getTransferAsset().getDestAccountId());
    assertEquals(0,
        new BigDecimal(userAmount)
            .compareTo(new BigDecimal(actualTransferCommand.getTransferAsset().getAmount()))
    );
    assertEquals(VAL_ASSET_ID, actualTransferCommand.getTransferAsset().getAssetId());
    assertTrue(actualSubtractCommand.hasSubtractAssetQuantity());
    assertEquals(VAL_ASSET_ID, actualSubtractCommand.getSubtractAssetQuantity().getAssetId());
    assertEquals(0,
        new BigDecimal(userAmount)
            .compareTo(new BigDecimal(actualSubtractCommand.getSubtractAssetQuantity().getAmount()))
    );
  }

  /**
   * @given {@link ValDistributionPluggableLogic} instance
   * @when VAL tokens transfer to brvs happens
   * @then {@link ValDistributionPluggableLogic} filters needed command and performs the
   * distribution of VALs to user accounts from {@link RegisteredUsersStorage} with burning of
   * nothing
   */
  @SneakyThrows
  @Test
  public void sunnyDayTestWithNoBurning() {
    final String userAmount = "100";
    initMocks(userAmount);
    valDistributionPluggableLogic.apply(block);
    final Transaction value = transactionArgumentCaptor.getValue();

    assertEquals(1, value.getPayload().getReducedPayload().getCommandsCount());
    final Command actualTransferCommand = value.getPayload().getReducedPayload().getCommands(0);
    assertTrue(actualTransferCommand.hasTransferAsset());
    assertEquals(BRVS_ID, actualTransferCommand.getTransferAsset().getSrcAccountId());
    assertEquals(USER_ID, actualTransferCommand.getTransferAsset().getDestAccountId());
    assertEquals(0,
        new BigDecimal(userAmount)
            .compareTo(new BigDecimal(actualTransferCommand.getTransferAsset().getAmount()))
    );
    assertEquals(VAL_ASSET_ID, actualTransferCommand.getTransferAsset().getAssetId());
  }

  /**
   * @given {@link ValDistributionPluggableLogic} instance
   * @when VAL tokens transfer to brvs happens
   * @then {@link ValDistributionPluggableLogic} filters needed command and performs burning of
   * supply since user proportion is zero
   */
  @SneakyThrows
  @Test
  public void sunnyDayTestWithBurningWholeSupply() {
    final String userAmount = "0";
    initMocks(userAmount);
    valDistributionPluggableLogic.apply(block);
    final Transaction value = transactionArgumentCaptor.getValue();

    assertEquals(1, value.getPayload().getReducedPayload().getCommandsCount());
    final Command actualSubtractCommand = value.getPayload().getReducedPayload().getCommands(0);
    assertTrue(actualSubtractCommand.hasSubtractAssetQuantity());
    assertEquals(VAL_ASSET_ID, actualSubtractCommand.getSubtractAssetQuantity().getAssetId());
    assertEquals(0,
        new BigDecimal(TOTAL_AMOUNT)
            .compareTo(new BigDecimal(actualSubtractCommand.getSubtractAssetQuantity().getAmount()))
    );
  }
}
