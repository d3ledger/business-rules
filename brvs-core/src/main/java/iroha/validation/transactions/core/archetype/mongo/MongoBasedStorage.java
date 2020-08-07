/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.archetype.mongo;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

import com.google.common.base.Strings;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.io.Closeable;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;

public abstract class MongoBasedStorage<T> implements Closeable {

  protected final MongoClient mongoClient;
  protected final MongoCollection<T> collection;

  public MongoBasedStorage(String mongoHost,
      int mongoPort,
      String databaseName,
      String collectionName,
      Class<T> typeParameterClass) {
    if (Strings.isNullOrEmpty(mongoHost)) {
      throw new IllegalArgumentException("MongoDB host must not be neither null nor empty");
    }
    if (mongoPort < 1 || mongoPort > 65535) {
      throw new IllegalArgumentException("MongoDB port must be valid");
    }
    if (Strings.isNullOrEmpty(databaseName)) {
      throw new IllegalArgumentException("Database name must not be neither null nor empty");
    }
    if (Strings.isNullOrEmpty(collectionName)) {
      throw new IllegalArgumentException("Collection name must not be neither null nor empty");
    }
    mongoClient = MongoClients
        .create(String.format("mongodb://%s:%d", mongoHost, mongoPort));
    CodecRegistry mongoVerdictCodecRegistry = fromRegistries(
        MongoClientSettings.getDefaultCodecRegistry(),
        fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );
    collection = mongoClient
        .getDatabase(databaseName)
        .getCollection(collectionName, typeParameterClass)
        .withCodecRegistry(mongoVerdictCodecRegistry);
  }

  @Override
  public void close() {
    mongoClient.close();
  }
}
