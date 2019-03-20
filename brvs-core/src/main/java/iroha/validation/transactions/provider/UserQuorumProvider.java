package iroha.validation.transactions.provider;

public interface UserQuorumProvider {

  /**
   * Method for getting relevant user related keypairs contained in users quorum
   *
   * @param targetAccount account id in Iroha
   * @return user quorum
   */
  int getUserQuorum(String targetAccount);

  /**
   * Method for setting relevant user related keypairs contained in users quorum
   *
   * @param targetAccount account id in Iroha
   * @param quorum user quorum to be set
   */
  void setUserQuorum(String targetAccount, int quorum);
}
