/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.provider;

import java.util.Collection;
import java.util.Set;
import java.util.function.Function;

public interface RegisteredUsersStorage {

  /**
   * Method for adding new user account entry to the storage
   *
   * @param accountId client account id in Iroha
   */
  void add(String accountId);

  /**
   * Method for checking if there is the user account given contained already
   *
   * @param accountId client account ids in Iroha
   * @return true if there is a specified entity
   */
  boolean contains(String accountId);

  /**
   * Method for getting all the registered user accounts
   *
   * @param method {@link Function} to apply to all the users contained
   * @return {@link Set} of specified type entries
   */
  <T> Set<T> process(Function<Iterable<String>, Collection<T>> method);
}
