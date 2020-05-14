/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.plugin.impl.sora;

import static iroha.validation.utils.ValidationUtils.REGISTRATION_BATCH_SIZE;
import static iroha.validation.utils.ValidationUtils.replaceLast;
import static jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter;

import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper;
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.QueryAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

public class ProjectAccountProvider {

  private static final Logger logger = LoggerFactory.getLogger(ProjectAccountProvider.class);
  public static final String ACCOUNT_PLACEHOLDER = "__";

  private final String accountsHolder;
  private final String accountsSetter;
  // accountname__domain -> description
  private final Map<String, String> projectDescriptions;

  public ProjectAccountProvider(
      String accountsHolder,
      String accountsSetter,
      QueryAPI queryAPI) {
    if (StringUtils.isEmpty(accountsHolder)) {
      throw new IllegalArgumentException(
          "Project accounts holder account must not be neither null nor empty"
      );
    }
    if (StringUtils.isEmpty(accountsSetter)) {
      throw new IllegalArgumentException(
          "Project accounts setter account must not be neither null nor empty"
      );
    }
    Objects.requireNonNull(queryAPI, "Query API must not be null");

    this.accountsHolder = accountsHolder;
    this.accountsSetter = accountsSetter;
    IrohaQueryHelper irohaQueryHelper = new IrohaQueryHelperImpl(queryAPI, REGISTRATION_BATCH_SIZE);
    projectDescriptions = irohaQueryHelper.getAccountDetails(accountsHolder, accountsSetter)
        .get()
        .entrySet()
        .stream()
        .collect(
            Collectors.toConcurrentMap(
                entry -> replaceLast(entry.getKey(), ACCOUNT_PLACEHOLDER, accountIdDelimiter),
                Entry::getValue
            )
        );
    logger.info(
        "Initialized project accounts provider with the list of projects: {}",
        projectDescriptions.toString()
    );
  }

  public boolean isProjectAccount(String accountId) {
    return projectDescriptions.containsKey(accountId);
  }

  public String getProjectDescription(String accountId) {
    return projectDescriptions.get(accountId);
  }

  public String addProjectWithDescription(String projectAccountId, String description) {
    logger.info("Adding a project: {} - {}", projectAccountId, description);
    return projectDescriptions
        .put(
            replaceLast(
                projectAccountId,
                ACCOUNT_PLACEHOLDER,
                accountIdDelimiter
            ),
            description
        );
  }

  public String getAccountsHolder() {
    return accountsHolder;
  }

  public String getAccountsSetter() {
    return accountsSetter;
  }
}
