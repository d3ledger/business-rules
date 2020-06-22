/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.plugin;

import static iroha.validation.utils.ValidationUtils.getTxAccountId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.AddSignatory;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.plugin.impl.QuorumReactionPluggableLogic;
import iroha.validation.transactions.provider.impl.AccountManager;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class QuorumReactionTest {

  private static final String DOMAIN = "sora";
  private static final String USERNAME = "user";
  private static final String USER_ID = USERNAME + "@" + DOMAIN;
  private static final String KEY = "KEY";
  private static final String KEY_TWO = "KEY_TWO";
  private Block block;
  private Transaction transaction;
  private QuorumReactionPluggableLogic quorumReactionPluggableLogic;
  private AccountManager accountManager;

  @BeforeEach
  public void initMocks() {
    block = mock(Block.class, RETURNS_DEEP_STUBS);
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    accountManager = mock(AccountManager.class);
    quorumReactionPluggableLogic = new QuorumReactionPluggableLogic(accountManager);
    when(accountManager.getUserDomains()).thenReturn(Collections.singleton(DOMAIN));
    when(accountManager.getRegisteredAccounts())
        .thenReturn(Collections.singleton(USER_ID));
    final Command command = mock(Command.class);
    final AddSignatory addSignatory = mock(AddSignatory.class);
    when(addSignatory.getAccountId()).thenReturn(USER_ID);
    when(addSignatory.getPublicKey()).thenReturn(KEY_TWO);
    when(command.hasAddSignatory()).thenReturn(true);
    when(command.getAddSignatory()).thenReturn(addSignatory);
    when(transaction.getPayload().getReducedPayload().getCommandsList())
        .thenReturn(Collections.singletonList(command));
    when(accountManager.getUserSignatoriesDetail(USER_ID))
        .thenReturn(Collections.singleton(KEY));
    when(accountManager.getValidQuorumForUserAccount(USER_ID))
        .thenReturn(2);
    when(block.getBlockV1().getPayload().getTransactionsList())
        .thenReturn(Collections.singletonList(transaction));
  }

  /**
   * @given {@link QuorumReactionPluggableLogic} instance and a user registered
   * @when the user adds another signatory to their account with a single key attached
   * @then {@link QuorumReactionPluggableLogic} filters needed command and modifies the quorum
   * detail and the user quorum with the {@link AccountManager} instance
   */
  @Test
  public void sunnyDayTest() {
    when(getTxAccountId(transaction)).thenReturn(USER_ID);

    quorumReactionPluggableLogic.apply(block);
    verify(accountManager).setUserQuorumDetail(eq(USER_ID), any());
    verify(accountManager).setUserAccountQuorum(eq(USER_ID), eq(2));
  }

  /**
   * @given {@link QuorumReactionPluggableLogic} instance and a user not being registered
   * @when the user adds another signatory to their account with a single key attached
   * @then {@link QuorumReactionPluggableLogic} filters needed command and nothing happens
   */
  @Test
  public void unknownAccountTest() {
    when(getTxAccountId(transaction)).thenReturn("unknown@sora");

    quorumReactionPluggableLogic.apply(block);
    verify(accountManager, never()).setUserQuorumDetail(eq(USER_ID), any());
    verify(accountManager, never()).setUserAccountQuorum(eq(USER_ID), eq(2));
  }
}
