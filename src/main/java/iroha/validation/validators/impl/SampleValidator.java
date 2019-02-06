package iroha.validation.validators.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.validators.Validator;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class SampleValidator implements Validator {

  private Collection<Rule> rules;


  public SampleValidator(Rule rule) {
    Objects.requireNonNull(rule, "Rule must not be null");
    this.rules = Collections.singletonList(rule);
  }

  public SampleValidator(Collection<Rule> rules) {
    if (CollectionUtils.isEmpty(rules)) {
      throw new IllegalArgumentException("Rules collection must not be neither null nor empty");
    }

    this.rules = rules;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean validate(Transaction transaction) {
    return rules.stream().allMatch(rule -> rule.isSatisfiedBy(transaction));
  }
}
