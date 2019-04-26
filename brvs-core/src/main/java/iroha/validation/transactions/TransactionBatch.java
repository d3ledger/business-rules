package iroha.validation.transactions;

import com.google.common.collect.ImmutableList;
import iroha.protocol.TransactionOuterClass.Transaction;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;

/**
 * Used to process not only single transaction but batches at once
 */
public class TransactionBatch implements Iterable<Transaction> {

  private final List<Transaction> transactionList;

  public TransactionBatch(List<Transaction> transactionList) {
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
}
