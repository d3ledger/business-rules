/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.verdict;

import java.util.Arrays;
import java.util.Collection;

public enum Verdict {
  UNKNOWN,
  PENDING,
  VALIDATED,
  REJECTED,
  FAILED;

  private static Collection<Verdict> terminateVerdicts = Arrays.asList(
      VALIDATED,
      REJECTED,
      FAILED
  );

  public static boolean checkIfVerdictIsTerminate(Verdict verdict) {
    return terminateVerdicts.contains(verdict);
  }
}
