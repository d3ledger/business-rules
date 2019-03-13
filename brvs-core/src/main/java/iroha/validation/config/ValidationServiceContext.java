package iroha.validation.config;

import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.util.BrvsData;
import iroha.validation.transactions.signatory.TransactionSigner;
import iroha.validation.validators.Validator;
import java.util.Collection;
import java.util.Objects;
import org.springframework.util.CollectionUtils;

public class ValidationServiceContext {

  private final Collection<Validator> validators;
  private final TransactionProvider transactionProvider;
  private final TransactionSigner transactionSigner;
  private final RegistrationProvider registrationProvider;
  private final BrvsData brvsData;
  private final boolean isRoot;

  public ValidationServiceContext(
      Collection<Validator> validators,
      TransactionProvider transactionProvider,
      TransactionSigner transactionSigner,
      RegistrationProvider registrationProvider,
      BrvsData brvsData,
      String isRoot) {
    if (CollectionUtils.isEmpty(validators)) {
      throw new IllegalArgumentException(
          "Validators collection must not be neither null nor empty");
    }
    Objects.requireNonNull(transactionProvider, "Transaction provider must not be null");
    Objects.requireNonNull(transactionSigner, "Transaction signer must not be null");
    Objects.requireNonNull(registrationProvider, "Registration provider must not be null");
    Objects.requireNonNull(brvsData, "BRVS data must not be null");

    this.validators = validators;
    this.transactionProvider = transactionProvider;
    this.transactionSigner = transactionSigner;
    this.registrationProvider = registrationProvider;
    this.brvsData = brvsData;
    this.isRoot = Boolean.parseBoolean(isRoot);
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

  public RegistrationProvider getRegistrationProvider() {
    return registrationProvider;
  }

  public BrvsData getBrvsData() {
    return brvsData;
  }

  public boolean isRoot() {
    return isRoot;
  }
}
