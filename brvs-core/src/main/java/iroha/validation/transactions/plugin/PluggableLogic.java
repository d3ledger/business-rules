/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin;

import iroha.protocol.TransactionOuterClass.Transaction;

/**
 * Generic interface to add any logic for transaction commitments
 *
 * @param <Processable> type of the processable entities
 */
public abstract class PluggableLogic<Processable> {

  /**
   * Performs filtering and a source objects transformation if needed
   *
   * @param sourceObjects {@link Iterable} of the source objects to process
   * @return <Processable> instance of a target object
   */
  abstract public Processable filterAndTransform(Iterable<Transaction> sourceObjects);

  /**
   * Applies a pluggable logic strictly after filtering and transformation
   *
   * @param sourceObjects {@link Iterable} of the source objects to process
   * @see PluggableLogic#filterAndTransform(Iterable)
   */
  public final void apply(Iterable<Transaction> sourceObjects) {
    applyInternal(filterAndTransform(sourceObjects));
  }

  /**
   * Internal method to contain a logic intended
   *
   * @param processableObject object to work with
   */
  abstract protected void applyInternal(Processable processableObject);
}
