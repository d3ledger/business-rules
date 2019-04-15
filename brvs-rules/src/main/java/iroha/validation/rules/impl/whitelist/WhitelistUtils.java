package iroha.validation.rules.impl.whitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import iroha.protocol.QryResponses;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WhitelistUtils {

  private static final Logger logger = LoggerFactory.getLogger(WhitelistUtils.class);


  public static final String ETH_WHITELIST_KEY = "eth_whitelist";
  public static final String BTC_WHITELIST_KEY = "btc_whitelist";

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
   *
   * @param brvsAccountId - brvs account id is query creator
   * @param brvsAccountKeyPair - for signing the query
   * @param irohaAPI - to send query
   * @param clientId - storage of details
   * @param whitelistKey - details key
   * @return Map of (address to validation time)
   * @throws IllegalAccessException on invalid response from Iroha
   */
  static Map<String, Long> getBRVSWhitelist(
      String brvsAccountId,
      KeyPair brvsAccountKeyPair,
      IrohaAPI irohaAPI,
      String clientId,
      String whitelistKey) throws IllegalAccessException {
    QryResponses.QueryResponse queryResponse = irohaAPI.query(Query
        .builder(brvsAccountId, 1L)
        .getAccountDetail(clientId, brvsAccountId, whitelistKey)
        .buildSigned(brvsAccountKeyPair));
    if (!queryResponse.hasAccountDetailResponse()) {
      throw new IllegalAccessException(
          "There is no valid response from Iroha about account details in account "
              + clientId + " setter " + clientId + "key " + whitelistKey);
    }

    String detail = queryResponse.getAccountDetailResponse().getDetail();
    logger.info("Got BRVS whitelist: " + detail);

    JsonObject accountNode = parser.parse(detail).getAsJsonObject();
    if (accountNode.get(brvsAccountId).isJsonNull()) {
      return new HashMap<>();
    }
    JsonObject keyNode = accountNode.getAsJsonObject(brvsAccountId);
    if (keyNode.get(whitelistKey).isJsonNull()) {
      return new HashMap<>();
    }
    String whitelistJSON = keyNode.getAsJsonPrimitive(whitelistKey).getAsString();

    return deserializeBRVSWhitelist(whitelistJSON);
  }

  /**
   * Get domain from asset id
   * @param assetId - string in form 'asset#domain'
   * @return domain of asset
   */
  public static String getAssetDomain(String assetId) {
    return assetId.substring(assetId.lastIndexOf("#") + 1);
  }

  /**
   * Deserialize BRVS whitelist from JSON
   *
   * @param json as list of Pair(Address, ValidationTime)
   * @return list of pairs as AddressValidation
   */
  public static Map<String, Long> deserializeBRVSWhitelist(String json) {
    return gson.fromJson(irohaUnEscape(json), new TypeToken<HashMap<String, Long>>() {
    }.getType());
  }

  public static String serializeBRVSWhitelist(Map<String, Long> list) {
    return gson.toJson(list);
  }

  /**
   * Deserialize client whitelist from JSON
   *
   * @param json as list of addresses
   * @return list of addresses
   */
  public static List<String> deserializeClientWhitelist(String json) {
    return gson.fromJson(irohaUnEscape(json), new TypeToken<List<String>>() {
    }.getType());
  }

  /**
   * Serialize client whitelist to escaped JSON
   *
   * @param lst of addresses
   * @return iroha friendly json string
   */
  public static String serializeClientWhitelist(List<String> lst) {
    return irohaEscape(gson.toJson(lst));
  }

  /**
   * Escapes symbols reserved in JSON so it can be used in Iroha
   */
  public static String irohaEscape(String str) {
    return str.replace("\"", IROHA_FRIENDLY_QUOTE)
        .replace("\n", IROHA_FRIENDLY_EOL);
  }

  /**
   * Reverse changes of 'irohaEscape'
   */
  public static String irohaUnEscape(String str) {
    return str.replace(IROHA_FRIENDLY_QUOTE, "\"")
        .replace(IROHA_FRIENDLY_EOL, "\n");
  }
}
