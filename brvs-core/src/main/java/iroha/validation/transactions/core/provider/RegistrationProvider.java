/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.provider;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public interface RegistrationProvider {

  /**
   * Method for registering user account for the service
   *
   * @param accountId client account id in Iroha
   */
  void register(String accountId) throws InterruptedException;

  /**
   * Method for registering many user accounts as a batch for the service
   *
   * @param accountIds {@link Collection} of client account ids in Iroha
   */
  void register(Collection<String> accountIds) throws InterruptedException;

  /**
   * Queries Iroha for all unregistered user accounts and applies the logic supplied
   *
   * @param method {@link Function} to apply to unregistered accounts
   */
  void processUnregisteredUserAccounts(Function<Set<String>, Object> method);

  /**
   * Provides the set of supported user domains of the instance
   *
   * @return {@link Set} of strings representing user domains in Iroha
   */
  Set<String> getUserDomains();

  /**
   * Method for checking if there is the user account given registered already
   *
   * @param accountId client account id in Iroha
   * @return true if there is a specified user registered
   */
  boolean isRegistered(String accountId);

  /**
   * Method for checking if there is the user account with the id provided
   *
   * @param accountId client account id in Iroha
   * @return true if there is a specified user in the system
   */
  boolean isUserAccount(String accountId);
}
