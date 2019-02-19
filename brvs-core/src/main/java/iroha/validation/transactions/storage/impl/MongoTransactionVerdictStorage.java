package iroha.validation.transactions.storage.impl;

import static com.mongodb.client.model.Filters.eq;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.springframework.stereotype.Component;

@Component
public class MongoTransactionVerdictStorage implements TransactionVerdictStorage {

  private static final ReplaceOptions replaceOptions = new ReplaceOptions().upsert(true);
  private static final String TX_HASH_ATTRIBUTE = "txHash";

  private final MongoClient mongoClient;
  private final MongoCollection<MongoVerdict> collection;
  private final PublishSubject<String> subject = PublishSubject.create();

  public MongoTransactionVerdictStorage() {
    mongoClient = MongoClients.create();
    CodecRegistry mongoVerdictCodecRegistry = fromRegistries(
        MongoClientSettings.getDefaultCodecRegistry(),
        fromProviders(PojoCodecProvider.builder().automatic(true).build()));
    collection = mongoClient
        .getDatabase("verdictStorage")
        .getCollection("verdicts", MongoVerdict.class)
        .withCodecRegistry(mongoVerdictCodecRegistry);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isHashPresentInStorage(String txHash) {
    return collection.find(eq(TX_HASH_ATTRIBUTE, txHash)).first() != null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionPending(String txHash) {
    store(txHash, ValidationResult.PENDING);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionValidated(String txHash) {
    store(txHash, ValidationResult.VALIDATED);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionRejected(String txHash, String reason) {
    store(txHash, ValidationResult.REJECTED(reason));
    subject.onNext(txHash);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult getTransactionVerdict(String txHash) {
    MongoVerdict verdict = collection.find(eq(TX_HASH_ATTRIBUTE, txHash)).first();
    return verdict == null ? null : verdict.result;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Observable<String> getRejectedTransactionsHashesStreaming() {
    return subject;
  }

  private synchronized void store(String txHash, ValidationResult result) {
    collection.replaceOne(eq(TX_HASH_ATTRIBUTE, txHash),
        new MongoVerdict(txHash, result),
        replaceOptions
    );
  }

  @Override
  public void close() {
    mongoClient.close();
  }

  public class MongoVerdict {

    private String txHash;
    private ValidationResult result;

    private MongoVerdict(String txHash, ValidationResult result) {
      this.txHash = txHash;
      this.result = result;
    }

    public String getTxHash() {
      return txHash;
    }

    public void setTxHash(String txHash) {
      this.txHash = txHash;
    }

    public ValidationResult getResult() {
      return result;
    }

    public void setResult(ValidationResult result) {
      this.result = result;
    }
  }
}
