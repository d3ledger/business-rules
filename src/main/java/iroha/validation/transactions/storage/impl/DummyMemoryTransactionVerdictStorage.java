package iroha.validation.transactions.storage.impl;

import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DummyMemoryTransactionVerdictStorage implements TransactionVerdictStorage {

  private final Map<String, ValidationResult> validationResultMap = new HashMap<>();

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isHashPresentInStorage(String txHash) {
    return validationResultMap.containsKey(txHash);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionPending(String txHash) {
    validationResultMap.put(txHash, ValidationResult.PENDING);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionValidated(String txHash) {
    validationResultMap.put(txHash, ValidationResult.VALIDATED);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionRejected(String txHash, String reason) {
    validationResultMap.put(txHash, ValidationResult.REJECTED(reason));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult getTransactionVerdict(String txHash) {
    return validationResultMap.get(txHash);
  }
}
