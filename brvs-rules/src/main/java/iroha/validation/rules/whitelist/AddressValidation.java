package iroha.validation.rules.whitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.util.Collection;
import java.util.List;

/**
 * Entity of BRVS whitelist. Pair
 */
public class AddressValidation {
    private String address;
    private long validatedTime;

    AddressValidation(String address, long validatedTime) {
        this.address = address;
        this.validatedTime = validatedTime;
    }

    public String getAddress() {
        return address;
    }

    public long getValidatedTime() {
        return validatedTime;
    }

    static Gson gson = new GsonBuilder().setPrettyPrinting().create();
    static JsonParser parser = new JsonParser();

    /**
     * Deserialize BRVS whitelist from JSON
     *
     * @param json as list of Pair(Address, ValidationTime)
     * @param key  - whitelist key (btc or eth)
     * @return list of pairs as AddressValidation
     */
    static List<AddressValidation> deserializeBRVSWhitelist(String json, String account, String key) {
        String oldWhitelistJSON = parser.parse(json)
                .getAsJsonObject()
                .getAsJsonObject(account)
                .getAsJsonPrimitive(key)
                .getAsString();
        return gson.fromJson(WhitelistUtils.irohaUnEscape(oldWhitelistJSON), new TypeToken<List<AddressValidation>>() {
        }.getType());
    }

    static String serializeBRVSWhitelist(Collection<AddressValidation> list) {
        return gson.toJson(list);
    }

}
