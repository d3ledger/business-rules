package iroha.validation.transactions.storage;

import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.QryResponses;
import iroha.protocol.TransactionOuterClass;
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
  Observable<TransactionOuterClass.Transaction> getPendingTransactionsStreaming();

  /**
   * Method providing new blocks coming from Iroha
   *
   * @return {@link Observable} of Iroha proto {@link QryResponses.BlockQueryResponse} block
   */
  Observable<Block> getBlockStreaming();

  /**
   * Method for saving transaction verdict as validated successfully to a storage
   *
   * @param txHash transaction hash
   */
  void markTransactionValidated(String txHash);


  /**
   * Method for saving transaction verdict as rejected by a reason to a storage
   *
   * @param txHash transaction hash
   * @param reason reason
   */
  void markTransactionRejected(String txHash, String reason);
}
