/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.transactions.provider.impl.util;

import java.util.concurrent.CountDownLatch;

/**
 * Wrapper for concurrent registration synchronization
 */
public class RegistrationAwaiterWrapper {

  private final CountDownLatch countDownLatch;

  private Exception exception;

  public RegistrationAwaiterWrapper(CountDownLatch countDownLatch) {
    this.countDownLatch = countDownLatch;
  }

  public CountDownLatch getCountDownLatch() {
    return countDownLatch;
  }

  public Exception getException() {
    return exception;
  }

  public void setException(Exception exception) {
    this.exception = exception;
  }
}
