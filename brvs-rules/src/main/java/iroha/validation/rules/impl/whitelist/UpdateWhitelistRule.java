package iroha.validation.rules.impl.whitelist;

import com.google.common.base.Strings;
import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass;
import iroha.validation.rules.Rule;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A rule that is triggered on a client whitelist update.
 * Checks the difference in new and old whitelist and updates BRVS records with validation period.
 */
public class UpdateWhitelistRule implements Rule {

    private static final Logger logger = LoggerFactory.getLogger(UpdateWhitelistRule.class);

    private String brvsAccountId;
    private KeyPair brvsAccountKeyPair;
    private IrohaAPI irohaAPI;

    /**
     * When new address will be valid
     */
    private long validationPeriod;

    public UpdateWhitelistRule(String brvsAccountId, KeyPair brvsAccountKeyPair, IrohaAPI irohaAPI, long validationPeriod) {
        logger.debug("START UpdateWhitelistRule");
        if (Strings.isNullOrEmpty(brvsAccountId)) {
            throw new IllegalArgumentException("Account ID must not be neither null nor empty");
        }
        this.brvsAccountId = brvsAccountId;

        Objects.requireNonNull(brvsAccountKeyPair, "Key pair must not be null");
        this.brvsAccountKeyPair = brvsAccountKeyPair;

        Objects.requireNonNull(irohaAPI, "Iroha API must not be null");
        this.irohaAPI = irohaAPI;

        this.validationPeriod = validationPeriod;
    }

    /**
     * Updates BRVS whitelist according to *client whitelist*
     * - if client added an address -> add the address to BRVS whitelist with new validation time
     * - if client removed an address -> remove it from BRVS whitelist
     *
     * @param transaction Iroha proto transaction
     * @return true
     */
    @Override
    public boolean isSatisfiedBy(TransactionOuterClass.Transaction transaction) {
        String clientId = transaction.getPayload().getReducedPayload().getCreatorAccountId();

        return transaction
                .getPayload()
                .getReducedPayload()
                .getCommandsList()
                .stream()
                .filter(Command::hasSetAccountDetail)
                .map(Command::getSetAccountDetail)
                .filter(tx -> tx.getAccountId().equals(clientId)
                        && (tx.getKey().equals(WhitelistUtils.ETH_WHITELIST_KEY)
                        || tx.getKey().equals(WhitelistUtils.BTC_WHITELIST_KEY)))
                .allMatch(tx -> {
                    try {
                        String whitelistKey = tx.getKey();
                        List<String> clientWhitelist = WhitelistUtils.deserializeClientWhitelist(tx.getValue());
                        logger.debug("Client " + clientId + " changed whitelist " + whitelistKey + " to " + clientWhitelist);

                        // get old whitelist that was set by BRVS as Pairs(address -> validation_time)
                        Map<String, Long> oldWhitelistValidated = WhitelistUtils.getBRVSWhitelist(
                                brvsAccountId,
                                brvsAccountKeyPair,
                                irohaAPI,
                                clientId,
                                whitelistKey
                        );

                        // When whitelist is validated
                        long validationTime = System.currentTimeMillis() / 1000 + validationPeriod;
                        logger.debug("ValidationTime: " + validationTime);

                        // Prepare new whitelist
                        // remove old addresses that user has removed
                        Map<String, Long> newWhitelistValidated = oldWhitelistValidated.entrySet().stream()
                                .filter(address -> clientWhitelist.contains(address.getKey()))
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                        // add new from user whitelist with new validationTime
                        newWhitelistValidated.putAll(
                                clientWhitelist.stream()
                                        .filter(address -> !oldWhitelistValidated.containsKey(address))
                                        .collect(Collectors.toMap(a -> a, t -> validationTime))
                        );

                        if (newWhitelistValidated.equals(oldWhitelistValidated)) {
                            logger.debug("No changes in whitelist, nothing to update");
                        } else {
                            String jsonFin = WhitelistUtils.serializeBRVSWhitelist(newWhitelistValidated);
                            logger.debug("Send whitelist to Iroha: " + jsonFin);

                            irohaAPI.transactionSync(
                                    Transaction.builder(brvsAccountId)
                                            .setAccountDetail(clientId, whitelistKey, WhitelistUtils.irohaEscape(jsonFin))
                                            .sign(brvsAccountKeyPair)
                                            .build()
                            );
                        }
                    } catch (Exception e) {
                        logger.error("Error during parsing JSON ", e);
                        return false;
                    }

                    return true;
                });
        }
}
