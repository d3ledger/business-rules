package iroha.validation.rules.impl.whitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import iroha.protocol.QryResponses;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class WhitelistUtils {

    static final String ETH_WHITELIST_KEY = "eth_whitelist";
    static final String BTC_WHITELIST_KEY = "btc_whitelist";

    static final Map<String, String> assetToWhitelistKey;

    static {
        assetToWhitelistKey = new HashMap<>();
        assetToWhitelistKey.put("ethereum", "eth_whitelist");
        assetToWhitelistKey.put("sora", "eth_whitelist");
        assetToWhitelistKey.put("bitcoin", "btc_whitelist");
    }


    // Iroha friendly symbols
    private static String IROHA_FRIENDLY_QUOTE = "\\\"";
    private static String IROHA_FRIENDLY_EOL = "\\n";

    private static Gson gson = new GsonBuilder().create();
    private static JsonParser parser = new JsonParser();

    /**
     * Get whitelist that was set by BRVS
     * @param brvsAccountId - brvs account id is query creator
     * @param brvsAccountKeyPair - for signing the query
     * @param irohaAPI - to send query
     * @param clientId - storage of details
     * @param whitelistKey - details key
     * @return Map of (address to validation time)
     * @throws Exception on invalid response from Iroha
     */
    static Map<String, Long> getBRVSWhitelist(
            String brvsAccountId,
            KeyPair brvsAccountKeyPair,
            IrohaAPI irohaAPI,
            String clientId,
            String whitelistKey) throws Exception {
        QryResponses.QueryResponse queryResponse = irohaAPI.query(Query
                .builder(brvsAccountId, 1L)
                .getAccountDetail(clientId, brvsAccountId, whitelistKey)
                .buildSigned(brvsAccountKeyPair));
        if (!queryResponse.hasAccountDetailResponse())
            throw new IllegalAccessException("There is no valid response from Iroha about account details in account "
                    + clientId + " setter " + clientId + "key " + whitelistKey);

        String detail = queryResponse.getAccountDetailResponse().getDetail();
        return deserializeBRVSWhitelist(detail, brvsAccountId, whitelistKey);
    }

    /**
     * Deserialize BRVS whitelist from JSON
     *
     * @param json as list of Pair(Address, ValidationTime)
     * @param key  - whitelist key (btc or eth)
     * @return list of pairs as AddressValidation
     */
    static Map<String, Long> deserializeBRVSWhitelist(String json, String account, String key) {
        JsonObject accountNode = parser.parse(json)
                .getAsJsonObject();
        if (accountNode.get(account).isJsonNull())
            return new HashMap<>();
        JsonObject keyNode = accountNode.getAsJsonObject(account);
        if (keyNode.get(key).isJsonNull())
            return new HashMap<>();
        String whitelistJSON = keyNode.getAsJsonPrimitive(key).getAsString();
        return gson.fromJson(irohaUnEscape(whitelistJSON), new TypeToken<HashMap<String, Long>>() {
        }.getType());
    }

    static String serializeBRVSWhitelist(Map<String, Long> list) {
        return gson.toJson(list);
    }

    /**
     * Deserialize client whitelist from JSON
     *
     * @param json as list of addresses
     * @return list of addresses
     */
    static List<String> deserializeClientWhitelist(String json) {
        return gson.fromJson(irohaUnEscape(json), new TypeToken<List<String>>() {
        }.getType());
    }

    /**
     * Escapes symbols reserved in JSON so it can be used in Iroha
     */
    static String irohaEscape(String str) {
        return str.replace("\"", IROHA_FRIENDLY_QUOTE)
                .replace("\n", IROHA_FRIENDLY_EOL);
    }

    /**
     * Reverse changes of 'irohaEscape'
     */
    static String irohaUnEscape(String str) {
        return str.replace(IROHA_FRIENDLY_QUOTE, "\"")
                .replace(IROHA_FRIENDLY_EOL, "\n");
    }

}
