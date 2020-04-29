/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider;

import iroha.validation.transactions.provider.impl.util.BrvsData;
import java.util.Collection;
import java.util.Set;

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
   * Method for getting all the registered user accounts
   *
   * @return {@link Set} of registered user accounts
   */
  Set<String> getRegisteredAccounts();

  /**
   * Queries Iroha for all user accounts
   *
   * @return {@link Set} of user accounts
   */
  Set<String> getUserAccounts();

  /**
   * Queries Iroha for all brvs instances data
   *
   * @return {@link Iterable} of user accounts
   */
  Iterable<BrvsData> getBrvsInstances();

  /**
   * Provides the set of supported user domains of the instance
   *
   * @return {@link Set} of strings representing user domains in Iroha
   */
  Set<String> getUserDomains();
}
