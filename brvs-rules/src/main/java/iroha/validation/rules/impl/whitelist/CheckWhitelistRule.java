package iroha.validation.rules.impl.whitelist;

import com.google.common.base.Strings;
import iroha.protocol.Commands;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.rules.Rule;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.util.Map;
import java.util.Objects;

/**
 * Rule that checks whitelisted addresses on withdrawal.
 */
public class CheckWhitelistRule implements Rule {

    private static final Logger logger = LoggerFactory.getLogger(UpdateWhitelistRule.class);

    private String withdrawalAccount = "notary@notary";

    private String brvsAccountId;
    private KeyPair brvsAccountKeyPair;
    private IrohaAPI irohaAPI;

    public CheckWhitelistRule(String brvsAccountId, KeyPair brvsAccountKeyPair, IrohaAPI irohaAPI) {
        logger.info("START CheckWhitelistRule");
        if (Strings.isNullOrEmpty(brvsAccountId)) {
            throw new IllegalArgumentException("Account ID must not be neither null nor empty");
        }
        this.brvsAccountId = brvsAccountId;

        Objects.requireNonNull(brvsAccountKeyPair, "Key pair must not be null");
        this.brvsAccountKeyPair = brvsAccountKeyPair;

        Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
        this.irohaAPI = irohaAPI;
    }

    @Override
    public boolean isSatisfiedBy(TransactionOuterClass.Transaction transaction) {
        logger.debug("Apply CheckWhitelistRule");

        return transaction
                .getPayload()
                .getReducedPayload()
                .getCommandsList()
                .stream()
                .filter(Commands.Command::hasTransferAsset)
                .map(Commands.Command::getTransferAsset)
                .filter(transfer -> transfer.getDestAccountId().equals(withdrawalAccount))
                .allMatch(transfer -> {
                            try {
                                String clientId = transfer.getSrcAccountId();
                                String address = transfer.getDescription();
                                String asset = transfer.getAssetId();
                                String assetDomain = asset.substring(asset.lastIndexOf("#") + 1);

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
                                    return false;
                                }

                                if (whitelistValidated.get(address) <= now) {
                                    logger.info("Transfer allowed.");
                                    return true;
                                } else {
                                    logger.info("Not allowed. Address " + address + "will be validated after "
                                            + whitelistValidated.get(address) + " now " + now);
                                    return false;
                                }

                            } catch (Exception e) {
                                logger.error("Error on parsing transfer", e);
                                return false;
                            }
                        }
                );
    }
}
