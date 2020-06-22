/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin;

import iroha.protocol.BlockOuterClass.Block;

/**
 * Generic interface to add any logic for transaction commitments
 *
 * @param <Processable> type of the processable entities
 */
public abstract class PluggableLogic<Processable> {

  /**
   * Performs filtering and a source object transformation if needed
   *
   * @param sourceObject {@link Iterable} of the source objects to process
   * @return <Processable> instance of a target object
   */
  abstract public Processable filterAndTransform(Block sourceObject);

  /**
   * Applies a pluggable logic strictly after filtering and transformation
   *
   * @param sourceObject {@link Iterable} of the source objects to process
   * @see PluggableLogic#filterAndTransform(Block)
   */
  public final void apply(Block sourceObject) {
    applyInternal(filterAndTransform(sourceObject));
  }

  /**
   * Internal method to contain a logic intended
   *
   * @param processableObject object to work with
   */
  abstract protected void applyInternal(Processable processableObject);
}
