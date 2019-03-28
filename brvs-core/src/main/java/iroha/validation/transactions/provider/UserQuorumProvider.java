package iroha.validation.transactions.provider;

public interface UserQuorumProvider {

  /**
   * Method for getting relevant user related keypairs contained in users quorum
   *
   * @param targetAccount account id in Iroha
   * @return user quorum
   */
  int getUserQuorumDetail(String targetAccount);

  /**
   * Method for setting relevant user related keypairs contained in users quorum
   *
   * @param targetAccount account id in Iroha
   * @param quorum user quorum to be set
   */
  void setUserQuorumDetail(String targetAccount, int quorum);

  /**
   * Method for setting relevant user Iroha account quorum
   *
   * @param targetAccount account id in Iroha
   * @param quorum account quorum to be set
   * @param creationTimeMillis time to synchronize operation
   */
  void setUserAccountQuorum(String targetAccount, int quorum, long creationTimeMillis);

  /**
   * Method for getting relevant account quorum with respect to brvs instaces count
   *
   * @param accountId account id in Iroha
   * @return quorum that should be used
   */
  int getValidQuorumForUserAccount(String accountId);
}
