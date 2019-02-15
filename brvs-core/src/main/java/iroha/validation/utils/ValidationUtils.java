package iroha.validation.utils;

import iroha.protocol.TransactionOuterClass.Transaction;
import jp.co.soramitsu.iroha.java.Utils;

public interface ValidationUtils {

  static String getTxAccountId(final Transaction transaction) {
    return transaction.getPayload().getReducedPayload().getCreatorAccountId();
  }

  static String hexHash(Transaction transaction) {
    return Utils.toHex(Utils.hash(transaction));
  }
}
