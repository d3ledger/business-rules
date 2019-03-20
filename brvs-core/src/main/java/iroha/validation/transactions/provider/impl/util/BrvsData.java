package iroha.validation.transactions.provider.impl.util;

public class BrvsData {

  private String hexPubKey;
  private String hostname;

  public BrvsData(String hexPubKey, String hostname) {
    this.hexPubKey = hexPubKey;
    this.hostname = hostname;
  }

  public String getHexPubKey() {
    return hexPubKey;
  }

  public String getHostname() {
    return hostname;
  }
}
