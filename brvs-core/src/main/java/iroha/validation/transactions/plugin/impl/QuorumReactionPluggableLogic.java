/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl;

import static iroha.validation.utils.ValidationUtils.getDomain;
import static iroha.validation.utils.ValidationUtils.getTxAccountId;

import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.plugin.PluggableLogic;
import iroha.validation.transactions.provider.impl.AccountManager;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signatories modification reactions processor
 */
public class QuorumReactionPluggableLogic extends PluggableLogic<Map<String, Collection<String>>> {

  private static final Logger logger = LoggerFactory.getLogger(QuorumReactionPluggableLogic.class);

  private final AccountManager accountManager;

  public QuorumReactionPluggableLogic(
      AccountManager accountManager) {
    Objects.requireNonNull(
        accountManager,
        "AccountManager must not be null"
    );
    this.accountManager = accountManager;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<String, Collection<String>> filterAndTransform(Block block) {
    final List<Transaction> transactions = block.getBlockV1().getPayload().getTransactionsList();
    if (transactions == null || transactions.isEmpty()) {
      return Collections.emptyMap();
    }

    final Set<String> userDomains = accountManager.getUserDomains();
    final Set<String> registeredAccounts = accountManager.getRegisteredAccounts();
    final List<Command> commands = transactions.stream()
        .map(blockTransaction -> {
              final String creatorAccountId = getTxAccountId(blockTransaction);
              if (!userDomains.contains(getDomainSafely(creatorAccountId))) {
                return Collections.<Command>emptyList();
              }

              if (!registeredAccounts.contains(creatorAccountId)) {
                return Collections.<Command>emptyList();
              }

              return blockTransaction
                  .getPayload()
                  .getReducedPayload()
                  .getCommandsList();
            }
        )
        .flatMap(Collection::stream)
        .collect(Collectors.toList());

    final Map<String, Set<String>> accountRemovedSignatories = constructRemovedSignatoriesByAccountId(
        commands,
        registeredAccounts
    );

    final Map<String, Set<String>> accountAddedSignatories = constructAddedSignatoriesByAccountId(
        commands,
        registeredAccounts
    );

    if (accountAddedSignatories.isEmpty() && accountRemovedSignatories.isEmpty()) {
      return Collections.emptyMap();
    }

    final Set<String> accountsKeysSet = new HashSet<>(accountAddedSignatories.keySet());
    accountsKeysSet.addAll(accountRemovedSignatories.keySet());

    return accountsKeysSet.stream().collect(
        Collectors.toMap(
            Function.identity(),
            accountId -> {
              final Set<String> userSignatories = new HashSet<>(
                  accountManager.getUserSignatoriesDetail(accountId)
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
                return Collections.emptyList();
              }
              return userSignatories;
            }
        )
    );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void applyInternal(Map<String, Collection<String>> processableObject) {
    processableObject.forEach((accountId, userSignatories) -> {
          logger.info("Going to modify account {} quorum", accountId);
          accountManager.setUserQuorumDetail(accountId, userSignatories);
          accountManager.setUserAccountQuorum(accountId,
              accountManager.getValidQuorumForUserAccount(accountId)
          );
        }
    );
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

  // For genesis blocks
  private String getDomainSafely(String accountId) {
    try {
      return getDomain(accountId);
    } catch (IndexOutOfBoundsException e) {
      logger.warn("Couldn't parse domain of " + accountId, e);
      return "";
    }
  }
}
