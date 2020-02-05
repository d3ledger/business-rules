/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

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
import iroha.protocol.BlockOuterClass.Block;
import iroha.validation.transactions.storage.BlockStorage;
import iroha.validation.utils.ValidationUtils;
import java.io.Closeable;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MongoBlockStorage implements BlockStorage, Closeable {

  private static final Logger logger = LoggerFactory.getLogger(MongoBlockStorage.class);
  private static final String BLOCK_HASH_ATTRIBUTE = "blockHash";
  private static final ReplaceOptions replaceOptions = new ReplaceOptions().upsert(true);

  private final MongoClient mongoClient;
  private final MongoCollection<MongoBlock> collection;

  public MongoBlockStorage(String mongoHost, int mongoPort) {
    if (Strings.isNullOrEmpty(mongoHost)) {
      throw new IllegalArgumentException("MongoDB host must not be neither null nor empty");
    }
    if (mongoPort < 1 || mongoPort > 65535) {
      throw new IllegalArgumentException("MongoDB port must be valid");
    }
    mongoClient = MongoClients
        .create(String.format("mongodb://%s:%d", mongoHost, mongoPort));
    CodecRegistry mongoVerdictCodecRegistry = fromRegistries(
        MongoClientSettings.getDefaultCodecRegistry(),
        fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );
    collection = mongoClient
        .getDatabase("blockStorage")
        .getCollection("blocks", MongoBlock.class)
        .withCodecRegistry(mongoVerdictCodecRegistry);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void store(Block irohaBlock) {
    // TODO Think about retrieval filters
    String blockHash = ValidationUtils.hexHash(irohaBlock);
    collection.replaceOne(eq(BLOCK_HASH_ATTRIBUTE, blockHash),
        new MongoBlock(blockHash, irohaBlock.toString()),
        replaceOptions
    );
    logger.info(
        "Saved new Iroha block in storage {}",
        irohaBlock.getBlockV1().getPayload().getHeight()
    );
  }

  @Override
  public void close() {
    mongoClient.close();
  }
}
