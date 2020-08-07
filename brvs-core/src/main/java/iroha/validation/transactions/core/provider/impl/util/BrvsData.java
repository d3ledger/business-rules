/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.provider.impl.util;

/**
 * Simple pojo to be used as an service instance identity
 */
public class BrvsData {

  private String hexPubKey;
  private String hostname;

  public BrvsData(String hexPubKey, String hostname) {
    this.hexPubKey = hexPubKey;
    this.hostname = hostname;
  }

  public String getHexPubKey() {
    return hexPubKey;
  }

  public String getHostname() {
    return hostname;
  }
}
