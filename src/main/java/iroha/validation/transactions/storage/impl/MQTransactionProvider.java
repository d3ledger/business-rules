package iroha.validation.transactions.storage.impl;

import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.storage.TransactionProvider;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

/**
 * {@link TransactionProvider} implementation on message queues
 */
public class MQTransactionProvider implements TransactionProvider {

  /**
   * {@inheritDoc}
   */
  @Override
  public Observable<Transaction> getPendingTransactionsStreaming() {
    // TODO
    throw new NotImplementedException();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Observable<Block> getBlockStreaming() {
    // TODO
    throw new NotImplementedException();
  }

  @Override
  public void close() {
    // TODO
    throw new NotImplementedException();
  }
}
