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
    this.rules = Collections.singletonList(new SampleRule());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Boolean validate(Transaction transaction) {
    return rules.stream().allMatch(rule -> rule.isSatisfiedBy(transaction));
  }
}
