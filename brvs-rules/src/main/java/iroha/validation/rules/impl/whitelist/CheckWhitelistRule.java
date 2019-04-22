package iroha.validation.rules.impl.whitelist;

import com.google.common.base.Strings;
import iroha.protocol.Commands;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.rules.Rule;
import iroha.validation.verdict.ValidationResult;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rule that checks whitelisted addresses on withdrawal.
 */
public class CheckWhitelistRule implements Rule {

  private static final Logger logger = LoggerFactory.getLogger(UpdateWhitelistRule.class);

  private final String brvsAccountId;
  private final KeyPair brvsAccountKeyPair;
  private final IrohaAPI irohaAPI;
  private final String withdrawalAccount;

  public CheckWhitelistRule(String brvsAccountId, KeyPair brvsAccountKeyPair, IrohaAPI irohaAPI,
      String withdrawalAccount) {
    if (Strings.isNullOrEmpty(brvsAccountId)) {
      throw new IllegalArgumentException("Account ID must not be neither null nor empty");
    }
    this.brvsAccountId = brvsAccountId;

    Objects.requireNonNull(brvsAccountKeyPair, "Key pair must not be null");
    this.brvsAccountKeyPair = brvsAccountKeyPair;

    Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
    this.irohaAPI = irohaAPI;

    this.withdrawalAccount = withdrawalAccount;
  }

  @Override
  public ValidationResult isSatisfiedBy(TransactionOuterClass.Transaction transaction) {
    logger.debug("Apply CheckWhitelistRule");

    return checkTransfers(
        transaction
            .getPayload()
            .getReducedPayload()
            .getCommandsList()
            .stream()
            .filter(Commands.Command::hasTransferAsset)
            .map(Commands.Command::getTransferAsset)
            .filter(transfer -> transfer.getDestAccountId().equals(withdrawalAccount))
            .collect(Collectors.toList())
    );
  }

  private ValidationResult checkTransfers(List<TransferAsset> transfers) {
    for (TransferAsset transfer : transfers) {
      try {
        String clientId = transfer.getSrcAccountId();
        String address = transfer.getDescription();
        String asset = transfer.getAssetId();
        String assetDomain = WhitelistUtils.getAssetDomain(asset);

        String whitelistKey = WhitelistUtils.assetToWhitelistKey.get(assetDomain);

        // get old whitelist that was set by BRVS as Pairs(address -> validation_time)
        Map<String, Long> whitelistValidated = WhitelistUtils.getBRVSWhitelist(
            brvsAccountId,
            brvsAccountKeyPair,
            irohaAPI,
            clientId,
            whitelistKey
        );

        long now = System.currentTimeMillis() / 1000;

        if (!whitelistValidated.containsKey(address)) {
          logger.info("Not allowed. " + address + " not in whitelist.");
          return ValidationResult.REJECTED(
              "Transfer not allowed. " + address + " not in whitelist."
          );
        }

        if (whitelistValidated.get(address) > now) {
          logger.info("Not allowed. Address " + address + " will be validated after "
              + whitelistValidated.get(address) + " now " + now);
          return ValidationResult.REJECTED(
              "Transfer not allowed. Address " + address + " will be validated after "
                  + whitelistValidated.get(address) + " now " + now
          );
        }
      } catch (Exception e) {
        logger.error("Error on parsing transfer", e);
        return ValidationResult.REJECTED("Error on parsing transfer: " + e.getMessage());
      }
    }
    logger.info("Transfer allowed.");
    return ValidationResult.VALIDATED;
  }
}
