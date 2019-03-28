package iroha.validation.rules.impl;

import iroha.protocol.Commands;
import iroha.protocol.Commands.Command;
import iroha.protocol.QryResponses.QueryResponse;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import java.io.StringReader;
import java.security.KeyPair;
import javax.json.Json;
import javax.json.JsonReader;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class BlockRule implements Rule {

  private static final Logger logger = LoggerFactory.getLogger(BlockRule.class);

  private IrohaAPI irohaAPI;
  private String blockerAccount = "blocker@trading";
  private String brvsAccountId;
  private KeyPair keyPair;

  public BlockRule(IrohaAPI irohaAPI, String brvsAccountId,
      KeyPair keyPair) {
    this.irohaAPI = irohaAPI;
    this.brvsAccountId = brvsAccountId;
    this.keyPair = keyPair;

  }


  private boolean validateTransfer(Commands.Command transfer, Transaction tx) {
    String creator = tx.getPayload().getReducedPayload().getCreatorAccountId();
    String from = transfer.getTransferAsset().getSrcAccountId();
    logger.info("Creator: {}\nFrom: {}", creator, from);
    if (!creator.equals(from)) {
      return true;
    }
    String assetId = transfer.getTransferAsset().getAssetId();
    double amount = Double.parseDouble(transfer.getTransferAsset().getAmount());
    logger.info("Asset: {}\nAmount: {}", assetId, amount);

    String key = assetId.replace("#", "___");
    QueryResponse queryResponse = irohaAPI.query(
        Query
            .builder(brvsAccountId, 1L)
            .getAccountDetail(creator, blockerAccount, key)
            .buildSigned(keyPair));

    String detail = queryResponse.getAccountDetailResponse().getDetail();
    logger.info("Detail: {}", detail);

    JsonReader reader = Json.createReader(new StringReader(detail));
    String str_limit = null;
    try {
      str_limit = reader
          .readObject()
          .getJsonObject(blockerAccount)
          .getString(key);
    } catch (Exception ex) {
      logger.info("Exception on parsing json detail: {}", ex);
      return true;
    }

    double limit = Double.parseDouble(str_limit);

    queryResponse = irohaAPI.query(
        Query
            .builder(brvsAccountId, 1L)
            .getAccountAssets(creator)
            .buildSigned(keyPair));

    double balance =
        queryResponse.getAccountAssetsResponse()
            .getAccountAssetsList()
            .stream()
            .filter(asset -> asset.getAssetId().equals(assetId))
            .mapToDouble(asset -> Double.parseDouble(asset.getBalance()))
            .sum();

    logger.info("Limit: {}\nBalance: {}", limit, balance);
    return balance - amount >= limit;

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSatisfiedBy(Transaction transaction) {
    logger.info("New transaction arrived");
    return transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasTransferAsset)
        .allMatch(transfer -> validateTransfer(transfer, transaction));
  }
}
