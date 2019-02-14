package iroha.validation.transactions.provider;

import io.reactivex.Observable;
import iroha.protocol.TransactionOuterClass;
import java.io.Closeable;

/**
 * Transaction provider interface Used to construct easy processable transaction queue
 */
public interface TransactionProvider extends Closeable {

  /**
   * Method that starts transaction fetching
   */
  void start();

  /**
   * Method providing new pending transactions coming from Iroha to be validated
   *
   * @return {@link Observable} of Iroha proto {@link TransactionOuterClass.Transaction} transaction
   */
  Observable<TransactionOuterClass.Transaction> getPendingTransactionsStreaming();

  /**
   * Method for registering account in order to monitor its pending transactions
   *
   * @param accountId account id in Iroha
   */
  void register(String accountId);
}
