package iroha.validation.validators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.validators.impl.SimpleAggregationValidator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ValidatorsTest {

  /**
   * @given {@link SimpleAggregationValidator}
   * @when {@link SimpleAggregationValidator} instantiated with an empty collection of rules
   * @then {@link IllegalArgumentException} is thrown
   */
  @Test
  void simpleAggregationValidatorWithEmptyArgumentTest() {
    assertThrows(IllegalArgumentException.class,
        () -> new SimpleAggregationValidator(Collections.emptySet()));
  }

  /**
   * @given {@link SimpleAggregationValidator} instantiated with lots of trivial rules always
   * returning true
   * @when Any {@link Transaction} is going to be validated by such a validator
   * @then The {@link Transaction} has been validated successfully
   */
  @Test
  void simpleAggregationValidatorWithManyTrueRulesTest() {
    Collection<Rule> rules = new ArrayList<>();
    for (int i = 0; i < 100500; i++) {
      rules.add(new SampleRule());
    }
    Validator validator = new SimpleAggregationValidator(rules);

    Transaction transaction = mock(Transaction.class);

    assertTrue(validator.validate(transaction));
  }

  /**
   * @given {@link SimpleAggregationValidator} instantiated with lots of trivial rules always
   * returning true and one returning false
   * @when Any {@link Transaction} is going to be validated by such a validator
   * @then The {@link Transaction} has NOT been validated
   */
  @Test
  void simpleAggregationValidatorWithManyRulesTest() {
    Collection<Rule> rules = new ArrayList<>();
    for (int i = 0; i < 100500; i++) {
      rules.add(new SampleRule());
    }
    rules.add(transaction -> false);
    Validator validator = new SimpleAggregationValidator(rules);

    Transaction transaction = mock(Transaction.class);

    assertFalse(validator.validate(transaction));
  }
}
