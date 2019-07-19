/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider.impl;

import com.google.common.base.Strings;
import io.reactivex.Observable;
import iroha.protocol.Commands.AddSignatory;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.RemoveSignatory;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicTransactionProvider implements TransactionProvider {

  private static final Logger logger = LoggerFactory.getLogger(BasicTransactionProvider.class);

  private final TransactionVerdictStorage transactionVerdictStorage;
  private final CacheProvider cacheProvider;
  private final UserQuorumProvider userQuorumProvider;
  private final RegistrationProvider registrationProvider;
  private final BlockStorage blockStorage;
  private final BrvsIrohaChainListener irohaReliableChainListener;
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);
  private final Set<String> userDomains;
  private boolean isStarted;

  public BasicTransactionProvider(
      TransactionVerdictStorage transactionVerdictStorage,
      CacheProvider cacheProvider,
      UserQuorumProvider userQuorumProvider,
      RegistrationProvider registrationProvider,
      BlockStorage blockStorage,
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
    this.blockStorage = blockStorage;
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
      executorService.scheduleAtFixedRate(this::monitorIrohaPending, 0, 2, TimeUnit.SECONDS);
      executorService.schedule(this::processBlockTransactions, 0, TimeUnit.SECONDS);
      executorService.schedule(this::processRejectedTransactions, 0, TimeUnit.SECONDS);
      isStarted = true;
    }
    return cacheProvider.getObservable();
  }

  private void monitorIrohaPending() {
    irohaReliableChainListener
        .getAllPendingTransactions(registrationProvider.getRegisteredAccounts())
        .forEach(transactionBatch -> {
              // if only BRVS signatory remains
              if (isBatchSignedByUsers(transactionBatch)) {
                if (savedMissingInStorage(transactionBatch)) {
                  cacheProvider.put(transactionBatch);
                }
              }
            }
        );
  }

  private boolean isBatchSignedByUsers(TransactionBatch transactionBatch) {
    for (Transaction transaction : transactionBatch) {
      if (transaction.getSignaturesCount() < userQuorumProvider
          .getUserSignatoriesDetail(ValidationUtils.getTxAccountId(transaction)).size()) {
        return false;
      }
    }
    return true;
  }

  private boolean savedMissingInStorage(TransactionBatch transactionBatch) {
    boolean result = false;
    for (Transaction transaction : transactionBatch) {
      final String hex = ValidationUtils.hexHash(transaction);
      if (!transactionVerdictStorage.isHashPresentInStorage(hex)) {
        transactionVerdictStorage.markTransactionPending(hex);
        result = true;
      }
    }
    return result;
  }

  private void processRejectedTransactions() {
    transactionVerdictStorage.getRejectedOrFailedTransactionsHashesStreaming()
        .subscribe(this::tryToRemoveLock);
  }

  private void processBlockTransactions() {
    irohaReliableChainListener.getBlockStreaming().subscribe(block -> {
          // Store new block first
          blockStorage.store(block);
          processCommitted(
              block
                  .getBlockV1()
                  .getPayload()
                  .getTransactionsList()
          );
        }
    );
    irohaReliableChainListener.listen();
  }

  private void processCommitted(List<Transaction> blockTransactions) {
    if (blockTransactions != null) {
      blockTransactions.forEach(transaction -> {
            tryToRemoveLock(transaction);
            try {
              modifyUserQuorumIfNeeded(transaction);
              registerCreatedAccountByTransactionScanning(transaction);
            } catch (Exception e) {
              logger.warn("Couldn't process account changes from the committed block", e);
            }
          }
      );
    }
  }

  private void modifyUserQuorumIfNeeded(Transaction blockTransaction) {
    final String creatorAccountId = ValidationUtils.getTxAccountId(blockTransaction);
    if (!userDomains.contains(getDomain(creatorAccountId))) {
      return;
    }

    final List<Command> commands = blockTransaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList();

    final Set<String> addedSignatories = commands
        .stream()
        .filter(Command::hasAddSignatory)
        .map(Command::getAddSignatory)
        .filter(command -> command.getAccountId().equals(creatorAccountId))
        .map(AddSignatory::getPublicKey)
        .collect(Collectors.toSet());
    final Set<String> removedSignatories = commands
        .stream()
        .filter(Command::hasRemoveSignatory)
        .map(Command::getRemoveSignatory)
        .filter(command -> command.getAccountId().equals(creatorAccountId))
        .map(RemoveSignatory::getPublicKey)
        .collect(Collectors.toSet());

    if (addedSignatories.isEmpty() && removedSignatories.isEmpty()) {
      return;
    }

    final Set<String> userSignatories = new HashSet<>(
        userQuorumProvider.getUserSignatoriesDetail(creatorAccountId));
    userSignatories.removeAll(removedSignatories);
    userSignatories.addAll(addedSignatories);
    final long syncTime = blockTransaction.getPayload().getReducedPayload().getCreatedTime();
    userQuorumProvider.setUserQuorumDetail(creatorAccountId,
        userSignatories,
        syncTime
    );
    userQuorumProvider.setUserAccountQuorum(creatorAccountId,
        userQuorumProvider.getValidQuorumForUserAccount(creatorAccountId),
        syncTime
    );
  }

  private void registerCreatedAccountByTransactionScanning(Transaction blockTransaction) {
    blockTransaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasCreateAccount)
        .map(Command::getCreateAccount)
        .filter(command -> userDomains.contains(command.getDomainId()))
        .forEach(command -> registrationProvider
            .register(String.format("%s@%s", command.getAccountName(), command.getDomainId()))
        );
  }

  private void tryToRemoveLock(Transaction transaction) {
    tryToRemoveLock(ValidationUtils.hexHash(transaction));
  }

  private void tryToRemoveLock(String hash) {
    cacheProvider.unlockPendingAccounts(cacheProvider.getAccountsBlockedBy(hash));
  }

  private String getDomain(String accountId) {
    return accountId.split("@")[1];
  }

  @Override
  public void close() {
    executorService.shutdownNow();
    irohaReliableChainListener.close();
  }
}
