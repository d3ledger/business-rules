/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.storage.impl.mongo;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.model.ReplaceOptions;
import iroha.protocol.BlockOuterClass.Block;
import iroha.validation.transactions.core.archetype.mongo.MongoBasedStorage;
import iroha.validation.transactions.core.storage.BlockStorage;
import iroha.validation.utils.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MongoBlockStorage extends MongoBasedStorage<MongoBlock> implements BlockStorage {

  private static final Logger logger = LoggerFactory.getLogger(MongoBlockStorage.class);
  private static final ReplaceOptions replaceOptions = new ReplaceOptions().upsert(true);
  private static final String BLOCK_HASH_ATTRIBUTE = "blockHash";
  private static final String DEFAULT_DB_NAME = "blockStorage";
  private static final String DEFAULT_COLLECTION_NAME = "blocks";

  public MongoBlockStorage(String mongoHost, int mongoPort) {
    super(mongoHost, mongoPort, DEFAULT_DB_NAME, DEFAULT_COLLECTION_NAME, MongoBlock.class);
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
}
