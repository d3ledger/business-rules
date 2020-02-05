/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.signatory;

import iroha.validation.transactions.TransactionBatch;

public interface TransactionSigner {

  /**
   * Method for signing validated transaction and sending it to Iroha peer
   *
   * @param transactionBatch Iroha proto transaction batch in brvs representation
   */
  void signAndSend(TransactionBatch transactionBatch);

  /**
   * Method for rejecting transaction with a reason
   *
   * @param transactionBatch Iroha proto transaction batch in brvs representation
   * @param reason reason
   */
  void rejectAndSend(TransactionBatch transactionBatch, String reason);
}
