package iroha.validation.rules.whitelist;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class WhitelistUtils {

    // Iroha friendly symbols
    private static String IROHA_FRIENDLY_QUOTE = "\\\"";
    private static String IROHA_FRIENDLY_EOL = "\\n";

    private static Gson gson = new GsonBuilder().create();
    private static JsonParser parser = new JsonParser();

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
