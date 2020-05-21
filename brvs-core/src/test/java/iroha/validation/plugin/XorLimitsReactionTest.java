/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.plugin;

import static iroha.validation.rules.impl.sora.XorWithdrawalLimitRule.ASSET_ID;
import static iroha.validation.transactions.plugin.impl.sora.XorWithdrawalLimitReactionPluggableLogic.LIMIT_AMOUNT_KEY;
import static iroha.validation.transactions.plugin.impl.sora.XorWithdrawalLimitReactionPluggableLogic.LIMIT_TIME_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper;
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl;
import io.reactivex.ObservableSource;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.SetAccountDetail;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.Endpoint.ToriiResponse;
import iroha.protocol.Endpoint.TxStatus;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.impl.sora.XorWithdrawalLimitRule.XorWithdrawalLimitRemainder;
import iroha.validation.transactions.plugin.impl.sora.XorWithdrawalLimitReactionPluggableLogic;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class XorLimitsReactionTest {

  private static final String DOMAIN = "sora";
  private static final String USERNAME = "user";
  private static final String USER_ID = USERNAME + "@" + DOMAIN;
  private final long time = System.currentTimeMillis();
  private final BigDecimal newLimit = new BigDecimal("150");
  private Transaction transaction;
  private XorWithdrawalLimitReactionPluggableLogic xorWithdrawalLimitReactionPluggableLogic;
  private final AtomicReference<XorWithdrawalLimitRemainder> atomicReference =
      spy(new AtomicReference<>());

  @BeforeEach
  public void initMocks() {
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    when(transaction.getPayload().getReducedPayload().getCreatorAccountId()).thenReturn(USER_ID);
    when(transaction.getPayload().getReducedPayload().getCreatedTime()).thenReturn(time);

    final Command commandTime = mock(Command.class);
    final SetAccountDetail setAccountDetailTime = mock(SetAccountDetail.class);
    when(setAccountDetailTime.getAccountId()).thenReturn(USER_ID);
    when(setAccountDetailTime.getKey()).thenReturn(LIMIT_TIME_KEY);
    when(setAccountDetailTime.getValue()).thenReturn(String.valueOf(time));

    final Command commandValue = mock(Command.class);
    final SetAccountDetail setAccountDetailValue = mock(SetAccountDetail.class);
    when(setAccountDetailValue.getAccountId()).thenReturn(USER_ID);
    when(setAccountDetailValue.getKey()).thenReturn(LIMIT_AMOUNT_KEY);
    when(setAccountDetailValue.getValue()).thenReturn(newLimit.toPlainString());

    final Command transferCommand = mock(Command.class);
    final TransferAsset transferAsset = mock(TransferAsset.class);
    when(transferAsset.getAssetId()).thenReturn(ASSET_ID);
    when(transferAsset.getAmount()).thenReturn(newLimit.toPlainString());
    when(transferAsset.getDestAccountId()).thenReturn(USER_ID);

    when(commandTime.hasSetAccountDetail()).thenReturn(true);
    when(commandTime.getSetAccountDetail()).thenReturn(setAccountDetailTime);

    when(commandValue.hasSetAccountDetail()).thenReturn(true);
    when(commandValue.getSetAccountDetail()).thenReturn(setAccountDetailValue);

    when(transferCommand.hasTransferAsset()).thenReturn(true);
    when(transferCommand.getTransferAsset()).thenReturn(transferAsset);

    when(transaction.getPayload().getReducedPayload().getCommandsList())
        .thenReturn(Arrays.asList(commandTime, commandValue, transferCommand));

    QueryAPI queryAPI = mock(QueryAPI.class);
    IrohaAPI irohaAPI = mock(IrohaAPI.class, RETURNS_DEEP_STUBS);
    ToriiResponse toriiResponse = mock(ToriiResponse.class);
    when(toriiResponse.getTxStatus()).thenReturn(TxStatus.COMMITTED);
    when(irohaAPI.transaction(any(), any()).takeUntil(any(ObservableSource.class)).blockingLast())
        .thenReturn(toriiResponse);
    when(queryAPI.getApi()).thenReturn(irohaAPI);
    when(queryAPI.getKeyPair()).thenReturn(new Ed25519Sha3().generateKeypair());

    IrohaQueryHelper irohaQueryHelper = mock(IrohaQueryHelperImpl.class, RETURNS_DEEP_STUBS);
    when(irohaQueryHelper.getAccountDetails(any(), any(), any()).get())
        .thenReturn(Optional.empty());

    xorWithdrawalLimitReactionPluggableLogic = new XorWithdrawalLimitReactionPluggableLogic(
        queryAPI,
        irohaQueryHelper,
        USER_ID,
        USER_ID,
        atomicReference,
        USER_ID
    );
  }

  /**
   * @given {@link XorWithdrawalLimitReactionPluggableLogic} instance and a transaction with updated
   * limits and a transfer
   * @when the transaction is being committed
   * @then {@link XorWithdrawalLimitReactionPluggableLogic} filters needed command and performs the
   * limits update
   */
  @Test
  public void sunnyDayTest() {
    xorWithdrawalLimitReactionPluggableLogic.apply(Collections.singleton(transaction));
    verify(atomicReference, times(3)).set(any());
  }
}
