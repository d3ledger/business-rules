/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions;

import com.google.common.collect.ImmutableList;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.utils.ValidationUtils;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.util.CollectionUtils;

/**
 * Used to process not only single transaction but batches at once
 */
public class TransactionBatch implements Iterable<Transaction> {

  private final List<Transaction> transactionList;

  public String getBatchInitiator() {
    return ValidationUtils.getTxAccountId(transactionList.get(0));
  }

  public TransactionBatch(List<Transaction> transactionList) {
    if (CollectionUtils.isEmpty(transactionList)) {
      throw new IllegalArgumentException("Batch transaction list cannot be null nor empty");
    }
    this.transactionList = ImmutableList.copyOf(transactionList);
  }

  public List<Transaction> getTransactionList() {
    return transactionList;
  }

  @Override
  public Iterator<Transaction> iterator() {
    return transactionList.iterator();
  }

  @Override
  public void forEach(Consumer<? super Transaction> action) {
    transactionList.forEach(action);
  }

  @Override
  public Spliterator<Transaction> spliterator() {
    return transactionList.spliterator();
  }

  public Stream<Transaction> stream() {
    return StreamSupport.stream(spliterator(), false);
  }
}
