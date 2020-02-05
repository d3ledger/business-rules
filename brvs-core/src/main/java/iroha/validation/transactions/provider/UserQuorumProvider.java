/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider;

import java.util.Set;

public interface UserQuorumProvider {

  /**
   * Method for getting relevant user related keypairs contained in users quorum
   *
   * @param targetAccount account id in Iroha
   * @return user signatories (public keys)
   */
  Set<String> getUserSignatoriesDetail(String targetAccount);

  /**
   * Method for setting relevant user related keypairs contained in users quorum
   *
   * @param targetAccount account id in Iroha
   * @param publicKeys user public keys to be set
   */
  void setUserQuorumDetail(String targetAccount,
      Iterable<String> publicKeys);

  /**
   * Method for getting relevant user Iroha account quorum
   *
   * @param targetAccount account id in Iroha
   */
  int getUserAccountQuorum(String targetAccount);

  /**
   * Method for setting relevant user Iroha account quorum
   *  @param targetAccount account id in Iroha
   * @param quorum account quorum to be set
   */
  void setUserAccountQuorum(String targetAccount, int quorum);

  /**
   * Method for getting relevant account quorum with respect to brvs instaces count
   *
   * @param accountId account id in Iroha
   * @return quorum that should be used
   */
  int getValidQuorumForUserAccount(String accountId);
}
