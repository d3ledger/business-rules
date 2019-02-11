package iroha.validation.transactions.provider.impl;

import io.reactivex.Observable;
import iroha.protocol.BlockOuterClass.Block;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.transactions.provider.TransactionProvider;
import org.springframework.stereotype.Component;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

/**
 * {@link TransactionProvider} implementation on message queues
 */
@Component
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
  public void register(String accountId) {
    // TODO
    throw new NotImplementedException();
  }

  @Override
  public void close() {
    // TODO
    throw new NotImplementedException();
  }
}
