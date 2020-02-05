/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.storage;

import iroha.protocol.BlockOuterClass;

public interface BlockStorage {

  /**
   * Stores Iroha block supplied
   *
   * @param irohaBlock {@link iroha.protocol.BlockOuterClass.Block} Iroha block
   */
  void store(BlockOuterClass.Block irohaBlock);
}
