/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.plugin;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.CreateAccount;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.plugin.impl.RegistrationReactionPluggableLogic;
import iroha.validation.transactions.provider.RegistrationProvider;
import java.util.Collections;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

public class RegistrationReactionTest {

  private static final String DOMAIN = "sora";
  private static final String USERNAME = "user";
  private static final String USER_ID = USERNAME + "@" + DOMAIN;
  private Block block;
  private Transaction transaction;
  private RegistrationReactionPluggableLogic registrationReactionPluggableLogic;
  private RegistrationProvider registrationProvider;

  @BeforeEach
  public void initMocks() {
    block = mock(Block.class, RETURNS_DEEP_STUBS);
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    registrationProvider = mock(RegistrationProvider.class);
    registrationReactionPluggableLogic = new RegistrationReactionPluggableLogic(
        registrationProvider
    );
    when(registrationProvider.getUserDomains()).thenReturn(Collections.singleton(DOMAIN));
    when(registrationProvider.getUserAccounts())
        .thenReturn(Collections.singleton(USER_ID));
    final Command command = mock(Command.class);
    final CreateAccount createAccount = mock(CreateAccount.class);
    when(createAccount.getAccountName()).thenReturn(USERNAME);
    when(createAccount.getDomainId()).thenReturn(DOMAIN);
    when(command.hasCreateAccount()).thenReturn(true);
    when(command.getCreateAccount()).thenReturn(createAccount);
    when(transaction.getPayload().getReducedPayload().getCommandsList())
        .thenReturn(Collections.singletonList(command));
    when(block.getBlockV1().getPayload().getTransactionsList())
        .thenReturn(Collections.singletonList(transaction));
  }

  /**
   * @given {@link RegistrationReactionPluggableLogic} instance and a user to register
   * @when the user is being registered
   * @then {@link RegistrationReactionPluggableLogic} filters needed command and performs the user
   * registration with the {@link RegistrationProvider} instance
   */
  @SneakyThrows
  @Test
  public void sunnyDayTest() {
    registrationReactionPluggableLogic.apply(block);
    verify(registrationProvider).register(ArgumentMatchers.anyCollection());
  }
}
