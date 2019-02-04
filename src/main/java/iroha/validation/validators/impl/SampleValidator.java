package iroha.validation.validators.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.validators.Validator;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

public class SampleValidator implements Validator {

  private Collection<Rule> rules;

  public SampleValidator() {
    this(new SampleRule());
  }

  public SampleValidator(Rule rule) {
    Objects.requireNonNull(rule, "Rule must not be null");
    this.rules = Collections.singletonList(rule);
  }

  public SampleValidator(Collection<Rule> rules) {
    Objects.requireNonNull(rules, "Rules collection must not be null");
    // TODO rework isEmpty check during springify task
    if(rules.isEmpty()) throw new IllegalArgumentException("Rules collection must not be empty");

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
