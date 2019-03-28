package iroha.validation.transactions.provider;

import iroha.validation.transactions.provider.impl.util.BrvsData;

public interface RegistrationProvider {

  /**
   * Method for registering user account for the service
   *
   * @param accountId client account id in Iroha
   * @param creationTimeMillis time to synchronize operation
   */
  void register(String accountId, long creationTimeMillis);

  /**
   * Method for getting all the registered user accounts
   *
   * @return {@link Iterable} of registered user accounts
   */
  Iterable<String> getRegisteredAccounts();

  /**
   * Queries Iroha for all user accounts
   *
   * @return {@link Iterable} of user accounts
   */
  Iterable<String> getUserAccounts();

  /**
   * Queries Iroha for all brvs instances data
   *
   * @return {@link Iterable} of user accounts
   */
  Iterable<BrvsData> getBrvsInstances();
}
