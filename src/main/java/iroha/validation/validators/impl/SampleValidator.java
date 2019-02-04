package iroha.validation.validators.impl;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.validators.Validator;
import java.util.Collection;
import java.util.Collections;

public class SampleValidator implements Validator {

  private Collection<Rule> rules;

  public SampleValidator() {
    this(new SampleRule());
  }

  public SampleValidator(Rule rule) {
    this(Collections.singletonList(rule));
  }

  public SampleValidator(Collection<Rule> rules) {
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
