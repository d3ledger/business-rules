/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.storage.impl.mongo;

public class MongoBlock {

  private String blockHash;
  private String blockContent;

  public MongoBlock() {
  }

  public MongoBlock(String blockHash, String blockContent) {
    this.blockHash = blockHash;
    this.blockContent = blockContent;
  }

  public String getBlockHash() {
    return blockHash;
  }

  public void setBlockHash(String blockHash) {
    this.blockHash = blockHash;
  }

  public String getBlockContent() {
    return blockContent;
  }

  public void setBlockContent(String blockContent) {
    this.blockContent = blockContent;
  }
}
