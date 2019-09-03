/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest.dto;

import java.util.Base64;
import java.util.Objects;

/**
 * Binary representation of protobuf transaction in hex.
 */
public final class BinaryTransaction {

  public final String hexString;

  public BinaryTransaction(String hexString) {
    Objects.requireNonNull(hexString);
    this.hexString = hexString;
  }

  public BinaryTransaction(byte[] bytes) {
    Objects.requireNonNull(bytes);
    hexString = Base64.getEncoder().encodeToString(bytes);
  }

}
