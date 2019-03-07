package iroha.validation.transactions.provider;

public interface RegistrationProvider {

  /**
   * Method for registering account for the service
   *
   * @param accountId client account id in Iroha
   */
  void register(String accountId);

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
}
