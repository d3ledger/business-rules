package iroha.validation.utils;

import iroha.protocol.TransactionOuterClass.Transaction;

public class ValidationUtils {

  private ValidationUtils() {
    throw new IllegalStateException("Util class cannot be instantiated");
  }

  public static String getTxAccountId(final Transaction transaction) {
    return transaction.getPayload().getReducedPayload().getCreatorAccountId();
  }
}
