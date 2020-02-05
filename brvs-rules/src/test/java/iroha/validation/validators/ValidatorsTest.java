/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.validators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.core.SampleRule;
import iroha.validation.validators.impl.SimpleAggregationValidator;
import iroha.validation.verdict.ValidationResult;
import iroha.validation.verdict.Verdict;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidatorsTest {

  /**
   * @given {@link SimpleAggregationValidator} instantiated with lots of trivial rules always
   * returning true
   * @when Any {@link Transaction} is going to be validated by such a validator
   * @then The {@link Transaction} has been validated successfully
   */
  @Test
  void simpleAggregationValidatorWithManyTrueRulesTest() {
    Map<String, Rule> rules = new HashMap<>();
    for (int i = 0; i < 100500; i++) {
      rules.put(String.valueOf(i), new SampleRule());
    }
    Validator validator = new SimpleAggregationValidator(rules);

    Transaction transaction = mock(Transaction.class);

    assertEquals(Verdict.VALIDATED,
        validator.validate(Collections.singleton(transaction)).getStatus());
  }

  /**
   * @given {@link SimpleAggregationValidator} instantiated with lots of trivial rules always
   * returning true and one returning false
   * @when Any {@link Transaction} is going to be validated by such a validator
   * @then The {@link Transaction} has NOT been validated
   */
  @Test
  void simpleAggregationValidatorWithManyRulesTest() {
    Map<String, Rule> rules = new HashMap<>();
    for (int i = 0; i < 100500; i++) {
      rules.put(String.valueOf(i), new SampleRule());
    }
    rules.put("badRule", transaction -> ValidationResult.REJECTED(""));
    Validator validator = new SimpleAggregationValidator(rules);

    Transaction transaction = mock(Transaction.class);

    assertEquals(Verdict.REJECTED,
        validator.validate(Collections.singleton(transaction)).getStatus());
  }
}
