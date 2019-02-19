package iroha.validation.config;

import iroha.validation.adapter.ChainAdapter;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.validators.Validator;
import java.util.Collection;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class ValidationServiceContext {

  private final Collection<Validator> validators;
  private final TransactionProvider transactionProvider;
  private final TransactionSigner transactionSigner;
  private final ChainAdapter chainAdapter;

  @Autowired
  public ValidationServiceContext(
      Collection<Validator> validators,
      TransactionProvider transactionProvider,
      TransactionSigner transactionSigner,
      ChainAdapter chainAdapter) {
    if (CollectionUtils.isEmpty(validators)) {
      throw new IllegalArgumentException(
          "Validators collection must not be neither null nor empty");
    }
    Objects.requireNonNull(transactionProvider, "Transaction provider must not be null");
    Objects.requireNonNull(transactionSigner, "Transaction signer must not be null");

    this.validators = validators;
    this.transactionProvider = transactionProvider;
    this.transactionSigner = transactionSigner;
    this.chainAdapter = chainAdapter;
  }

  public ChainAdapter getChainAdapter() {
    return chainAdapter;
  }

  public Collection<Validator> getValidators() {
    return validators;
  }

  public TransactionProvider getTransactionProvider() {
    return transactionProvider;
  }

  public TransactionSigner getTransactionSigner() {
    return transactionSigner;
  }
}
