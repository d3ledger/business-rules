/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.exception;

public enum BrvsErrorCode {
  OK,
  REGISTRATION_TIMEOUT,
  REGISTRATION_FAILED,
  WRONG_DOMAIN,
  UNKNOWN_ACCOUNT,
  FIELD_VALIDATION_ERROR
}
