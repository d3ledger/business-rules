package iroha.validation.transactions.storage;

public interface TransactionVerdictStorage {

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
}
