package iroha.validation.utils;

import iroha.protocol.TransactionOuterClass.Transaction;
import jp.co.soramitsu.iroha.java.Utils;

public class ValidationUtils {

  private ValidationUtils() {
    throw new IllegalStateException("Util class cannot be instantiated");
  }

  public static String getTxAccountId(final Transaction transaction) {
    return transaction.getPayload().getReducedPayload().getCreatorAccountId();
  }

  public static String hexHash(Transaction transaction) {
    return Utils.toHex(Utils.hash(transaction));
  }
}
