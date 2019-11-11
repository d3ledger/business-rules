/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider;

import iroha.validation.transactions.provider.impl.util.BrvsData;
import java.util.Set;

public interface RegistrationProvider {

  /**
   * Method for registering user account for the service
   *
   * @param accountId client account id in Iroha
   */
  void register(String accountId);

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
}
