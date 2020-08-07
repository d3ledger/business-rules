/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.provider.impl;

import static com.d3.commons.util.ThreadUtilKt.createPrettyScheduledThreadPool;
import static com.d3.commons.util.ThreadUtilKt.createPrettySingleThreadPool;
import static iroha.validation.utils.ValidationUtils.getTxAccountId;
import static iroha.validation.utils.ValidationUtils.hexHash;

import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.listener.BrvsIrohaChainListener;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.transactions.core.provider.RegistrationProvider;
import iroha.validation.transactions.core.provider.TransactionProvider;
import iroha.validation.transactions.core.provider.UserQuorumProvider;
import iroha.validation.transactions.core.storage.TransactionVerdictStorage;
import iroha.validation.transactions.plugin.PluggableLogic;
import iroha.validation.utils.ValidationUtils;
import iroha.validation.verdict.ValidationResult;
import iroha.validation.verdict.Verdict;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service interacting with cached user transactions queues and block listener
 */
public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final TransactionVerdictStorage transactionVerdictStorage;
  private final UserQuorumProvider userQuorumProvider;
  private final RegistrationProvider registrationProvider;
  private final BrvsIrohaChainListener irohaReliableChainListener;
  private final List<PluggableLogic<?>> pluggableLogicList;
  private final int pendingPollingPeriod;
  private final ScheduledExecutorService executor = createPrettyScheduledThreadPool(
      "brvs", "pending-processor"
  );
  private final Scheduler blockScheduler = Schedulers.from(createPrettySingleThreadPool(
      "brvs", "block-processor"
  ));
  // Observable
  private final PublishSubject<TransactionBatch> subject = PublishSubject.create();
  private boolean isStarted;

  public BasicTransactionProvider(
      TransactionVerdictStorage transactionVerdictStorage,
      UserQuorumProvider userQuorumProvider,
      RegistrationProvider registrationProvider,
      BrvsIrohaChainListener irohaReliableChainListener,
      List<PluggableLogic<?>> pluggableLogicList,
      String pendingPollingPeriod) {
    Objects.requireNonNull(
        transactionVerdictStorage,
        "TransactionVerdictStorage must not be null"
    );
    Objects.requireNonNull(
        userQuorumProvider,
        "UserQuorumProvider must not be null"
    );
    Objects.requireNonNull(
        registrationProvider,
        "RegistrationProvider must not be null"
    );
    Objects.requireNonNull(
        irohaReliableChainListener,
        "IrohaReliableChainListener must not be null"
    );
    Objects.requireNonNull(
        pluggableLogicList,
        "Pluggable logics List must not be null"
    );

    this.transactionVerdictStorage = transactionVerdictStorage;
    this.userQuorumProvider = userQuorumProvider;
    this.registrationProvider = registrationProvider;
    this.pluggableLogicList = pluggableLogicList;
    this.irohaReliableChainListener = irohaReliableChainListener;
    this.pendingPollingPeriod = Integer.parseInt(pendingPollingPeriod);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Observable<TransactionBatch> getPendingTransactionsStreaming() {
    if (!isStarted) {
      logger.info("Starting pending transactions streaming");
      executor.scheduleAtFixedRate(
          this::monitorIrohaPending,
          5,
          pendingPollingPeriod,
          TimeUnit.SECONDS
      );
      processBlockTransactions(blockScheduler);
      isStarted = true;
    }
    return subject;
  }

  private void monitorIrohaPending() {
    try {
      irohaReliableChainListener.getAllPendingTransactions()
          .forEach(transactionBatch -> {
                // if only BRVS signatory remains
                if (isBatchSignedByUsers(transactionBatch)) {
                  if (savedMissingInStorage(transactionBatch)) {
                    logger.info("Publishing {} transactions for validation", hexHash(transactionBatch));
                    subject.onNext(transactionBatch);
                  }
                }
              }
          );
    } catch (Exception e) {
      logger.error("Pending transactions monitor encountered an error", e);
      System.exit(1);
    }
  }

  private boolean isBatchSignedByUsers(TransactionBatch transactionBatch) {
    return transactionBatch
        .stream()
        .filter(transaction -> registrationProvider.isRegistered(getTxAccountId(transaction)))
        .allMatch(transaction ->
            transaction.getSignaturesCount() >= getSignatoriesToPresentNum(transaction)
        );
  }

  private int getSignatoriesToPresentNum(Transaction transaction) {
    final String creatorAccountId = getTxAccountId(transaction);
    int signatoriesToPresent = userQuorumProvider
        .getUserSignatoriesDetail(creatorAccountId).size();
    if (signatoriesToPresent == 0) {
      signatoriesToPresent =
          userQuorumProvider.getUserAccountQuorum(creatorAccountId) / ValidationUtils.PROPORTION;
    }
    return signatoriesToPresent;
  }

  private boolean savedMissingInStorage(TransactionBatch transactionBatch) {
    return transactionBatch
        .stream()
        .map(ValidationUtils::hexHash)
        .filter(hash -> !checkIfBatchStatusTerminate(hash))
        .map(transactionVerdictStorage::markTransactionPending)
        .findAny()
        .orElse(false);
  }

  private boolean checkIfBatchStatusTerminate(String hash) {
    final ValidationResult transactionVerdict = transactionVerdictStorage
        .getTransactionVerdict(hash);
    if (transactionVerdict == null) {
      return false;
    }
    return Verdict.checkIfVerdictIsTerminate(transactionVerdict.getStatus());
  }

  private void processBlockTransactions(Scheduler scheduler) {
    irohaReliableChainListener.getBlockStreaming()
        .observeOn(scheduler)
        .subscribe(blockSubscription -> {
              try {
                pluggableLogicList.forEach(pluggableLogic ->
                    pluggableLogic.apply(blockSubscription.getBlock())
                );
                blockSubscription.getAcknowledgment().ack();
              } catch (Exception e) {
                logger.error("Block processor encountered an error", e);
                System.exit(1);
              }
            }
        );
    irohaReliableChainListener.listen();
  }

  @Override
  public void close() throws IOException {
    executor.shutdownNow();
    blockScheduler.shutdown();
    irohaReliableChainListener.close();
  }
}
