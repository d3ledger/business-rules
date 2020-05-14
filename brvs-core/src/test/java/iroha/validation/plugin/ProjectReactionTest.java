/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.plugin;

import static iroha.validation.transactions.plugin.impl.sora.ProjectAccountProvider.ACCOUNT_PLACEHOLDER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.SetAccountDetail;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.plugin.impl.sora.NewProjectReactionPluggableLogic;
import iroha.validation.transactions.plugin.impl.sora.ProjectAccountProvider;
import java.util.Collections;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

public class ProjectReactionTest {

  private static final String DOMAIN = "sora";
  private static final String USERNAME = "user";
  private static final String USER_ID = USERNAME + "@" + DOMAIN;
  private static final String DESCRIPTION = "DESCRIPTION";
  private Transaction transaction;
  private NewProjectReactionPluggableLogic newProjectReactionPluggableLogic;
  private ProjectAccountProvider projectAccountProvider;

  @BeforeEach
  public void initMocks() {
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    when(transaction.getPayload().getReducedPayload().getCreatorAccountId()).thenReturn(USER_ID);
    projectAccountProvider = mock(ProjectAccountProvider.class);
    newProjectReactionPluggableLogic = new NewProjectReactionPluggableLogic(
        projectAccountProvider
    );
    when(projectAccountProvider.getAccountsHolder()).thenReturn(USER_ID);
    when(projectAccountProvider.getAccountsSetter()).thenReturn(USER_ID);
    when(projectAccountProvider.getProjectDescription(any())).thenReturn(DESCRIPTION);
    final Command command = mock(Command.class);
    final SetAccountDetail setAccountDetail = mock(SetAccountDetail.class);
    when(setAccountDetail.getAccountId()).thenReturn(USER_ID);
    when(setAccountDetail.getKey()).thenReturn(USERNAME + ACCOUNT_PLACEHOLDER + DOMAIN);
    when(setAccountDetail.getValue()).thenReturn(DESCRIPTION);
    when(command.hasSetAccountDetail()).thenReturn(true);
    when(command.getSetAccountDetail()).thenReturn(setAccountDetail);
    when(transaction.getPayload().getReducedPayload().getCommandsList())
        .thenReturn(Collections.singletonList(command));
  }

  /**
   * @given {@link NewProjectReactionPluggableLogic} instance and a project to register
   * @when the project is being registered
   * @then {@link NewProjectReactionPluggableLogic} filters needed command and performs the project
   * registration with the {@link ProjectAccountProvider} instance
   */
  @SneakyThrows
  @Test
  public void sunnyDayTest() {
    newProjectReactionPluggableLogic.apply(Collections.singleton(transaction));
    verify(projectAccountProvider).addProjectWithDescription(
        ArgumentMatchers.eq(USERNAME + ACCOUNT_PLACEHOLDER + DOMAIN),
        ArgumentMatchers.eq(DESCRIPTION)
    );
  }
}
