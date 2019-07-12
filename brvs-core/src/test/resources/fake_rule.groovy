/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */


import iroha.protocol.TransactionOuterClass
import iroha.validation.rules.Rule
import iroha.validation.verdict.ValidationResult

class BadRule implements Rule {

    @Override
    ValidationResult isSatisfiedBy(TransactionOuterClass.Transaction transaction) {
        return ValidationResult.REJECTED("HELLO FROM BAD RULE");
    }
}
