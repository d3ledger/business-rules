/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.core.provider.impl;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.model.ReplaceOptions;
import iroha.validation.transactions.core.archetype.mongo.MongoBasedStorage;
import iroha.validation.transactions.core.provider.RegisteredUsersStorage;
import iroha.validation.transactions.core.provider.impl.RegisteredUsersStorageImpl.UserAccountId;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class RegisteredUsersStorageImpl extends MongoBasedStorage<UserAccountId>
    implements RegisteredUsersStorage {

  private static final ReplaceOptions optionsToReplace = new ReplaceOptions().upsert(true);
  private static final String USER_NAME_ATTRIBUTE = "userId";
  private static final int DEFAULT_PAGE_SIZE = 500;
  private static final String DEFAULT_DB_NAME = "userStorage";
  private static final String DEFAULT_COLLECTION_NAME = "users";

  public RegisteredUsersStorageImpl(String mongoHost, int mongoPort) {
    super(mongoHost, mongoPort, DEFAULT_DB_NAME, DEFAULT_COLLECTION_NAME, UserAccountId.class);
  }

  @Override
  public void add(String accountId) {
    collection.replaceOne(eq(USER_NAME_ATTRIBUTE, accountId),
        new UserAccountId(accountId),
        optionsToReplace
    );
  }

  @Override
  public boolean contains(String accountId) {
    return collection.find(eq(USER_NAME_ATTRIBUTE, accountId)).first() != null;
  }

  @Override
  public <T> Set<T> process(Function<Iterable<String>, Collection<T>> method) {
    final Set<T> resultSet = new HashSet<>();
    int pageCounter = 0;
    Set<String> accountsPage;
    do {
      accountsPage = getAccountsPage(pageCounter);
      resultSet.addAll(method.apply(accountsPage));
      pageCounter++;
    } while (!accountsPage.isEmpty());
    return resultSet;
  }

  private Set<String> getAccountsPage(int pageNum) {
    final Set<String> accountsPage = new HashSet<>();
    collection.find().skip(DEFAULT_PAGE_SIZE * pageNum).limit(DEFAULT_PAGE_SIZE).forEach(
        (Consumer<? super UserAccountId>) account -> accountsPage.add(account.getUserId())
    );
    return accountsPage;
  }

  public static class UserAccountId {

    private String userId;

    public UserAccountId(String userId) {
      this.userId = userId;
    }

    public String getUserId() {
      return userId;
    }

    // for mongo deserialization

    public UserAccountId() {

    }

    public void setUserId(String userId) {
      this.userId = userId;
    }
  }
}
