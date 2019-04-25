package iroha.validation.transactions.provider;

import io.reactivex.Observable;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.transactions.TransactionBatch;
import java.io.Closeable;

/**
 * Transaction provider interface Used to construct easy processable transaction queue
 */
public interface TransactionProvider extends Closeable {

  /**
   * Method providing new pending transactions coming from Iroha to be validated
   *
   * @return {@link Observable} of Iroha proto {@link TransactionOuterClass.Transaction} transaction
   */
  Observable<TransactionBatch> getPendingTransactionsStreaming();
}
