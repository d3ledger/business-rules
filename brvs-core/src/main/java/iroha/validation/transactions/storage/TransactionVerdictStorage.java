package iroha.validation.transactions.storage;

import io.reactivex.Observable;
import iroha.validation.verdict.ValidationResult;
import java.io.Closeable;

public interface TransactionVerdictStorage extends Closeable {

  /**
   * Method for indicating if hash provided is contained in a storage
   *
   * @param txHash transaction hash
   */
  boolean isHashPresentInStorage(String txHash);

  /**
   * Method for saving (new) transaction verdict as pending to a storage
   *
   * @param txHash transaction hash
   */
  void markTransactionPending(String txHash);

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

  /**
   * Method for saving transaction verdict as failed by a reason to a storage
   *
   * @param txHash transaction hash
   * @param reason reason
   */
  void markTransactionFailed(String txHash, String reason);

  /**
   * Method for retrieving transaction validation verdict
   *
   * @param txHash transaction hash
   */
  ValidationResult getTransactionVerdict(String txHash);

  /**
   * Method providing arriving rejected verdicts transactions
   *
   * @return {@link Observable} of transactions hashes
   */
  Observable<String> getRejectedOrFailedTransactionsHashesStreaming();
}
