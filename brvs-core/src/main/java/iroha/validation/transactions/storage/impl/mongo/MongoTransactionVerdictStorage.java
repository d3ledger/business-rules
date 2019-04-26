package iroha.validation.transactions.storage.impl.mongo;

import static com.mongodb.client.model.Filters.eq;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

import com.google.common.base.Strings;
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

public class MongoTransactionVerdictStorage implements TransactionVerdictStorage {

  private static final ReplaceOptions replaceOptions = new ReplaceOptions().upsert(true);
  private static final String TX_HASH_ATTRIBUTE = "txHash";

  private final MongoClient mongoClient;
  private final MongoCollection<MongoVerdict> collection;
  private final PublishSubject<String> subject = PublishSubject.create();

  public MongoTransactionVerdictStorage(String mongoHost, int mongoPort) {
    if (Strings.isNullOrEmpty(mongoHost)) {
      throw new IllegalArgumentException("MongoDB host must not be neither null nor empty");
    }
    if (mongoPort < 1 || mongoPort > 65535) {
      throw new IllegalArgumentException("MongoDB port must be valid");
    }
    mongoClient = MongoClients.create(String.format("mongodb://%s:%d", mongoHost, mongoPort));
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
    return collection.find(eq(TX_HASH_ATTRIBUTE, txHash.toUpperCase())).first() != null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionPending(String txHash) {
    store(txHash.toUpperCase(), ValidationResult.UNKNOWN);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionValidated(String txHash) {
    store(txHash.toUpperCase(), ValidationResult.VALIDATED);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionRejected(String txHash, String reason) {
    final String upperCaseHash = txHash.toUpperCase();
    store(upperCaseHash, ValidationResult.REJECTED(reason));
    subject.onNext(upperCaseHash);
  }

  @Override
  public void markTransactionFailed(String txHash, String reason) {
    final String upperCaseHash = txHash.toUpperCase();
    store(upperCaseHash, ValidationResult.FAILED(reason));
    subject.onNext(upperCaseHash);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult getTransactionVerdict(String txHash) {
    MongoVerdict verdict = collection.find(eq(TX_HASH_ATTRIBUTE, txHash.toUpperCase())).first();
    return verdict == null ? null : verdict.getResult();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Observable<String> getRejectedOrFailedTransactionsHashesStreaming() {
    return subject;
  }

  private void store(String txHash, ValidationResult result) {
    final String upperCaseHash = txHash.toUpperCase();
    collection.replaceOne(eq(TX_HASH_ATTRIBUTE, upperCaseHash),
        new MongoVerdict(upperCaseHash, result),
        replaceOptions
    );
  }

  @Override
  public void close() {
    mongoClient.close();
  }
}
