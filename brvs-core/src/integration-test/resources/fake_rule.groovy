/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


import iroha.protocol.TransactionOuterClass
import iroha.validation.rules.Rule
import iroha.validation.verdict.ValidationResult

class BadRule implements Rule {

    @Override
    ValidationResult isSatisfiedBy(TransactionOuterClass.Transaction transaction) {
        if (transaction.payload.reducedPayload.commandsList.stream().anyMatch { command -> command.hasTransferAsset() }) {
            return ValidationResult.REJECTED("HELLO FROM BAD RULE")
        }
        return ValidationResult.VALIDATED
    }
}
