/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider.impl;

import static com.d3.commons.util.ThreadUtilKt.createPrettyScheduledThreadPool;
import static com.d3.commons.util.ThreadUtilKt.createPrettySingleThreadPool;
import static iroha.validation.utils.ValidationUtils.getTxAccountId;
import static jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter;

import com.google.common.base.Strings;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.listener.BrvsIrohaChainListener;
import iroha.validation.transactions.TransactionBatch;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.UserQuorumProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.storage.BlockStorage;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.utils.ValidationUtils;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service interacting with cached user transactions queues and block listener
 */
public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final TransactionVerdictStorage transactionVerdictStorage;
  private final CacheProvider cacheProvider;
  private final UserQuorumProvider userQuorumProvider;
  private final RegistrationProvider registrationProvider;
  private final BrvsIrohaChainListener irohaReliableChainListener;
  private final ScheduledExecutorService executor = createPrettyScheduledThreadPool(
      "brvs", "pending-processor"
  );
  private final Scheduler blockScheduler = Schedulers.from(createPrettySingleThreadPool(
      "brvs", "block-processor"
  ));
  private final Scheduler rejectScheduler = Schedulers.from(createPrettySingleThreadPool(
      "brvs", "rejects-processor"
  ));
  private final Set<String> userDomains;
  private boolean isStarted;

  public BasicTransactionProvider(
      TransactionVerdictStorage transactionVerdictStorage,
      CacheProvider cacheProvider,
      UserQuorumProvider userQuorumProvider,
      RegistrationProvider registrationProvider,
      BrvsIrohaChainListener irohaReliableChainListener,
      String userDomains
  ) {
    Objects.requireNonNull(transactionVerdictStorage, "TransactionVerdictStorage must not be null");
    Objects.requireNonNull(cacheProvider, "CacheProvider must not be null");
    Objects.requireNonNull(userQuorumProvider, "UserQuorumProvider must not be null");
    Objects.requireNonNull(registrationProvider, "RegistrationProvider must not be null");
    Objects
        .requireNonNull(irohaReliableChainListener, "IrohaReliableChainListener must not be null");
    if (Strings.isNullOrEmpty(userDomains)) {
      throw new IllegalArgumentException("User domains string must not be null nor empty");
    }

    this.transactionVerdictStorage = transactionVerdictStorage;
    this.cacheProvider = cacheProvider;
    this.userQuorumProvider = userQuorumProvider;
    this.registrationProvider = registrationProvider;
    this.irohaReliableChainListener = irohaReliableChainListener;
    this.userDomains = Arrays.stream(userDomains.split(",")).collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized Observable<TransactionBatch> getPendingTransactionsStreaming() {
    if (!isStarted) {
      logger.info("Starting pending transactions streaming");
      executor.scheduleAtFixedRate(this::monitorIrohaPending, 0, 2, TimeUnit.SECONDS);
      processBlockTransactions(blockScheduler);
      processRejectedTransactions(rejectScheduler);
      isStarted = true;
    }
    return cacheProvider.getObservable();
  }

  private void monitorIrohaPending() {
    try {
      final Set<String> accounts = registrationProvider.getRegisteredAccounts();
      irohaReliableChainListener
          .getAllPendingTransactions(accounts)
          .forEach(transactionBatch -> {
                // if only BRVS signatory remains
                if (isBatchSignedByUsers(transactionBatch, accounts)) {
                  saveMissingInStorage(transactionBatch);
                  cacheProvider.put(transactionBatch);
                }
              }
          );
    } catch (Exception e) {
      logger.error("Pending transactions monitor encountered an error", e);
      System.exit(1);
    }
  }

  private boolean isBatchSignedByUsers(TransactionBatch transactionBatch,
      Set<String> userAccounts) {
    return transactionBatch
        .stream()
        .filter(transaction -> userAccounts.contains(getTxAccountId(transaction)))
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

  private boolean saveMissingInStorage(TransactionBatch transactionBatch) {
    return transactionBatch
        .stream()
        .map(ValidationUtils::hexHash)
        .filter(hash -> !transactionVerdictStorage.isHashPresentInStorage(hash))
        .map(transactionVerdictStorage::markTransactionPending)
        .findAny()
        .orElse(false);
  }

  private void processRejectedTransactions(Scheduler scheduler) {
    transactionVerdictStorage.getRejectedOrFailedTransactionsHashesStreaming()
        .observeOn(scheduler)
        .subscribe(this::tryToRemoveLock);
  }

  private void processBlockTransactions(Scheduler scheduler) {
    irohaReliableChainListener.getBlockStreaming()
        .observeOn(scheduler)
        .subscribe(blockSubscription -> {
              try {
                // Store new block first
                final Block block = blockSubscription.getBlock();
                processCommitted(
                    block
                        .getBlockV1()
                        .getPayload()
                        .getTransactionsList()
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

  private void processCommitted(List<Transaction> blockTransactions) {
    if (blockTransactions != null) {
      blockTransactions.forEach(transaction -> {
            tryToRemoveLock(transaction);
            try {
              registerCreatedAccountByTransactionScanning(transaction);
              modifyUserQuorumIfNeeded(transaction);
            } catch (Exception e) {
              throw new IllegalStateException(
                  "Couldn't process account changes from the committed block", e
              );
            }
          }
      );
    }
  }

  private void modifyUserQuorumIfNeeded(Transaction blockTransaction) {
    final String creatorAccountId = getTxAccountId(blockTransaction);
    if (!userDomains.contains(getDomain(creatorAccountId))) {
      return;
    }

    final Set<String> registeredAccounts = registrationProvider.getRegisteredAccounts();
    if (!registeredAccounts.contains(creatorAccountId)) {
      return;
    }

    final List<Command> commands = blockTransaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList();

    modifyUserQuorum(commands, registeredAccounts);
  }

  private void modifyUserQuorum(Collection<Command> commands, Set<String> registeredAccounts) {

    final Map<String, Set<String>> accountRemovedSignatories = constructRemovedSignatoriesByAccountId(
        commands,
        registeredAccounts
    );

    final Map<String, Set<String>> accountAddedSignatories = constructAddedSignatoriesByAccountId(
        commands,
        registeredAccounts
    );

    if (accountAddedSignatories.isEmpty() && accountRemovedSignatories.isEmpty()) {
      return;
    }

    final Set<String> accountsKeysSet = accountAddedSignatories.keySet();
    accountsKeysSet.addAll(accountRemovedSignatories.keySet());

    for (final String accountId : accountsKeysSet) {
      final Set<String> userSignatories = new HashSet<>(
          userQuorumProvider.getUserSignatoriesDetail(accountId)
      );
      final Set<String> removedSignatories = accountRemovedSignatories.get(accountId);
      final Set<String> addedSignatories = accountAddedSignatories.get(accountId);
      if (removedSignatories != null) {
        userSignatories.removeAll(removedSignatories);
      }
      if (addedSignatories != null) {
        userSignatories.addAll(addedSignatories);
      }
      if (userSignatories.isEmpty()) {
        logger.warn("There was an attempt to delete all keys of {}", accountId);
        return;
      }
      logger.info("Going to modify account {} quorum", accountId);
      userQuorumProvider.setUserQuorumDetail(accountId, userSignatories);
      userQuorumProvider.setUserAccountQuorum(accountId,
          userQuorumProvider.getValidQuorumForUserAccount(accountId)
      );
    }
  }

  private Map<String, Set<String>> constructRemovedSignatoriesByAccountId(
      Collection<Command> commands,
      Set<String> registeredAccounts) {
    final Map<String, Set<String>> accountRemovedSignatories = new HashMap<>();

    commands
        .stream()
        .filter(Command::hasRemoveSignatory)
        .map(Command::getRemoveSignatory)
        .filter(command -> registeredAccounts.contains(command.getAccountId()))
        .forEach(removeSignatory -> {
          final String signatoryAccountId = removeSignatory.getAccountId();
          if (!accountRemovedSignatories.containsKey(signatoryAccountId)) {
            accountRemovedSignatories.put(signatoryAccountId, new HashSet<>());
          }
          accountRemovedSignatories.get(signatoryAccountId)
              .add(removeSignatory.getPublicKey().toUpperCase());
        });
    return accountRemovedSignatories;
  }

  private Map<String, Set<String>> constructAddedSignatoriesByAccountId(
      Collection<Command> commands,
      Set<String> registeredAccounts) {
    final Map<String, Set<String>> accountAddedSignatories = new HashMap<>();

    commands
        .stream()
        .filter(Command::hasAddSignatory)
        .map(Command::getAddSignatory)
        .filter(command -> registeredAccounts.contains(command.getAccountId()))
        .forEach(addSignatory -> {
          final String signatoryAccountId = addSignatory.getAccountId();
          if (!accountAddedSignatories.containsKey(signatoryAccountId)) {
            accountAddedSignatories.put(signatoryAccountId, new HashSet<>());
          }
          accountAddedSignatories.get(signatoryAccountId)
              .add(addSignatory.getPublicKey().toUpperCase());
        });
    return accountAddedSignatories;
  }

  private void registerCreatedAccountByTransactionScanning(Transaction blockTransaction)
      throws InterruptedException {
    List<String> createAccountList = blockTransaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasCreateAccount)
        .map(Command::getCreateAccount)
        .filter(command -> userDomains.contains(command.getDomainId()))
        .map(command -> command.getAccountName().concat(accountIdDelimiter)
            .concat(command.getDomainId()))
        .collect(Collectors.toList());
    if (!createAccountList.isEmpty()) {
      final Set<String> userAccounts = registrationProvider.getUserAccounts();
      createAccountList = createAccountList
          .stream()
          .filter(userAccounts::contains)
          .collect(Collectors.toList());
      registrationProvider.register(createAccountList);
    }
  }

  private void tryToRemoveLock(Transaction transaction) {
    tryToRemoveLock(ValidationUtils.hexHash(transaction));
  }

  private void tryToRemoveLock(String hash) {
    cacheProvider.unlockPendingAccountsByHash(hash);
  }

  private String getDomain(String accountId) {
    try {
      return accountId.split("@")[1];
    } catch (Exception e) {
      logger.warn("Couldn't parse domain of " + accountId, e);
      return "";
    }
  }

  @Override
  public void close() throws IOException {
    executor.shutdownNow();
    blockScheduler.shutdown();
    rejectScheduler.shutdown();
    irohaReliableChainListener.close();
  }
}
