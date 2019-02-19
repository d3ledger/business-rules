package iroha.validation.transactions.storage.impl.dummy;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DummyMemoryTransactionVerdictStorage implements TransactionVerdictStorage {

  private final Map<String, ValidationResult> validationResultMap = new HashMap<>();
  private final PublishSubject<String> subject = PublishSubject.create();

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
    subject.onNext(txHash);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult getTransactionVerdict(String txHash) {
    return validationResultMap.get(txHash);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Observable<String> getRejectedTransactionsHashesStreaming() {
    return subject;
  }

  @Override
  public void close() {

  }
}
