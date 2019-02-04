package iroha.validation.transactions.storage;

import io.reactivex.Observable;
import iroha.protocol.QryResponses;
import iroha.protocol.TransactionOuterClass;

/**
 * Transaction provider interface Used to construct easy processable transaction queue
 */
public interface TransactionProvider {

  /**
   * Method providing new pending transactions coming from Iroha to be validated
   *
   * @return {@link Observable} of Iroha proto {@link TransactionOuterClass.Transaction} transaction
   */
  Observable<TransactionOuterClass.Transaction> getPendingTransactionsStreaming();

  /**
   * Method providing new blocks coming from Iroha
   *
   * @return {@link Observable} of Iroha proto {@link QryResponses.BlockQueryResponse} block
   */
  Observable<QryResponses.BlockQueryResponse> getBlockStreaming();
}
