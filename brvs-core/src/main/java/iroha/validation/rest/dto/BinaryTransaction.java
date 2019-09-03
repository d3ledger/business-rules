/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rest.dto;

import java.util.Base64;

/**
 * Binary representation of protobuf transaction in hex.
 */
public final class BinaryTransaction {

  public BinaryTransaction(String hexString) {
    this.hexString = hexString;
  }

  public BinaryTransaction(byte[] bytes) {
    hexString = Base64.getEncoder().encodeToString(bytes);
  }

  public final String hexString;
}
