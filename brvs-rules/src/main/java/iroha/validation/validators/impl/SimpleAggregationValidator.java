package iroha.validation.validators.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.validators.Validator;
import iroha.validation.verdict.ValidationResult;
import iroha.validation.verdict.Verdict;
import java.util.Collection;
import org.springframework.util.CollectionUtils;

public class SimpleAggregationValidator implements Validator {

  private Collection<Rule> rules;

  public SimpleAggregationValidator(Collection<Rule> rules) {
    if (CollectionUtils.isEmpty(rules)) {
      throw new IllegalArgumentException("Rules collection must not be neither null nor empty");
    }

    this.rules = rules;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult validate(Transaction transaction) {
    for (Rule rule : rules) {
      final ValidationResult validationResult = rule.isSatisfiedBy(transaction);
      if (validationResult.getStatus().equals(Verdict.REJECTED)) {
        return validationResult;
      }
    }
    return ValidationResult.VALIDATED;
  }
}
