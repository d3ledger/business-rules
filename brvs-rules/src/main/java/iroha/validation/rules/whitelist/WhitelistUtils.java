package iroha.validation.rules.whitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.util.List;

class WhitelistUtils {

    // Iroha friendly symbols
    static String IROHA_FRIENDLY_QUOTE = "\\\"";
    static String IROHA_FRIENDLY_EOL = "\\n";

    static private Gson gson = new GsonBuilder().create();

    /**
     * Deserialize client whitelist from JSON
     *
     * @param json as list of addresses
     * @return list of addresses
     */
    static List<String> deserializeClientWhitelist(String json) {
        return gson.fromJson(WhitelistUtils.irohaUnEscape(json), new TypeToken<List<String>>() {
        }.getType());
    }

    /**
     * Escapes symbols reserved in JSON so it can be used in Iroha
     */
    static String irohaEscape(String str) {
        return str.replace("\"", IROHA_FRIENDLY_QUOTE)
                .replace("\n", IROHA_FRIENDLY_EOL);
    }

    // Reverse changes of 'irohaEscape'
    static String irohaUnEscape(String str) {
        return str.replace(IROHA_FRIENDLY_QUOTE, "\"")
                .replace(IROHA_FRIENDLY_EOL, "\n");
    }

}
