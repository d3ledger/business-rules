/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.storage.impl.mongo;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.model.ReplaceOptions;
import iroha.validation.transactions.core.archetype.mongo.MongoBasedStorage;
import iroha.validation.transactions.core.storage.TransactionVerdictStorage;
import iroha.validation.verdict.ValidationResult;

public class MongoTransactionVerdictStorage extends MongoBasedStorage<MongoVerdict>
    implements TransactionVerdictStorage {

  private static final ReplaceOptions optionsToReplace = new ReplaceOptions().upsert(true);
  private static final ReplaceOptions optionsToKeep = new ReplaceOptions().upsert(false);
  private static final String TX_HASH_ATTRIBUTE = "txHash";
  private static final String DEFAULT_DB_NAME = "verdictStorage";
  private static final String DEFAULT_COLLECTION_NAME = "verdicts";

  public MongoTransactionVerdictStorage(String mongoHost, int mongoPort) {
    super(mongoHost, mongoPort, DEFAULT_DB_NAME, DEFAULT_COLLECTION_NAME, MongoVerdict.class);
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
  public boolean markTransactionPending(String txHash) {
    store(txHash.toUpperCase(), ValidationResult.PENDING, optionsToKeep);
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionValidated(String txHash) {
    store(txHash.toUpperCase(), ValidationResult.VALIDATED, optionsToReplace);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markTransactionRejected(String txHash, String reason) {
    store(txHash.toUpperCase(), ValidationResult.REJECTED(reason), optionsToReplace);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ValidationResult getTransactionVerdict(String txHash) {
    MongoVerdict verdict = collection.find(eq(TX_HASH_ATTRIBUTE, txHash.toUpperCase())).first();
    return verdict == null ? null : verdict.getResult();
  }

  private void store(String txHash, ValidationResult result, ReplaceOptions options) {
    final String upperCaseHash = txHash.toUpperCase();
    collection.replaceOne(eq(TX_HASH_ATTRIBUTE, upperCaseHash),
        new MongoVerdict(upperCaseHash, result),
        options
    );
  }
}
