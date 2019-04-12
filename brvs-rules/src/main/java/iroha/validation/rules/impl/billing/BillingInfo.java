package iroha.validation.rules.impl.billing;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BillingInfo {

  private static final DateTimeFormatter isoLocalDateTime = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
  private static final String ACCOUNT_ID_ATTRIBUTE = "accountId";
  private static final String BILLING_TYPE_ATTRIBUTE = "billingType";
  private static final String ASSET_ATTRIBUTE = "asset";
  private static final String FEE_FRACTION_ATTRIBUTE = "feeFraction";
  private static final String CREATED_ATTRIBUTE = "created";
  private static final String UPDATED_ATTRIBUTE = "updated";

  private String domain;
  private BillingTypeEnum billingType;
  private String asset;
  private BigDecimal feeFraction;
  private LocalDateTime updated;

  private BillingInfo(
      String domain,
      BillingTypeEnum billingType,
      String asset,
      BigDecimal feeFraction,
      LocalDateTime updated) {

    this.domain = domain;
    this.billingType = billingType;
    this.asset = asset;
    this.feeFraction = feeFraction;
    this.updated = updated;
  }

  public enum BillingTypeEnum {
    TRANSFER("transfer"),
    CUSTODY("custody"),
    ACCOUNT_CREATION("accountCreation"),
    EXCHANGE("exchange"),
    WITHDRAWAL("withdrawal");

    public final String label;

    /* default */ BillingTypeEnum(String label) {
      this.label = label;
    }

    /* default */ static BillingTypeEnum valueOfLabel(String label) {
      for (BillingTypeEnum e : values()) {
        if (e.label.equals(label)) {
          return e;
        }
      }
      throw new BillingTypeException("Cannot parse label: " + label);
    }

    private static class BillingTypeException extends RuntimeException {

      /* default */ BillingTypeException(String s) {
        super(s);
      }
    }
  }

  /* default */ static Set<BillingInfo> parseBillingHttpDto(String billingType,
      Map<String, Map<String, JsonObject>> domainsMap) {

    final Set<BillingInfo> result = new HashSet<>();
    domainsMap.forEach(
        (domain, assetsMap) -> assetsMap.forEach(
            (asset, info) -> result.add(
                new BillingInfo(
                    domain,
                    BillingTypeEnum.valueOfLabel(billingType),
                    asset,
                    info.get(FEE_FRACTION_ATTRIBUTE).getAsBigDecimal(),
                    parseDateTime(info.get(CREATED_ATTRIBUTE).getAsString())
                )
            )
        )
    );

    return result;
  }

  private static LocalDateTime parseDateTime(String s) {
    // We take (0,22) since Z is not processed by the formatter
    return LocalDateTime.parse(s.substring(0, 22), isoLocalDateTime);
  }

  /* default */ static BillingInfo parseBillingMqDto(JsonObject object) {
    return new BillingInfo(
        getDomain(object.getAsJsonPrimitive(ACCOUNT_ID_ATTRIBUTE).getAsString()),
        BillingTypeEnum.valueOfLabel(object.getAsJsonPrimitive(BILLING_TYPE_ATTRIBUTE)
            .getAsString()),
        object.getAsJsonPrimitive(ASSET_ATTRIBUTE).getAsString(),
        object.getAsJsonPrimitive(FEE_FRACTION_ATTRIBUTE).getAsBigDecimal(),
        parseDateTime(object.getAsJsonPrimitive(UPDATED_ATTRIBUTE).getAsString())
    );
  }

  /* default */ static String getDomain(String accountId) {
    return accountId.split("@")[1];
  }

  /* default */ static String getName(String accountId) {
    return accountId.split("@")[0];
  }

  @Override
  public int hashCode() {
    return (domain + billingType.name() + asset + feeFraction.toPlainString()).hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!this.getClass().isAssignableFrom(other.getClass())) {
      return false;
    }
    return other.hashCode() == this.hashCode();
  }

  /* default */ String getDomain() {
    return domain;
  }

  /* default */ BillingTypeEnum getBillingType() {
    return billingType;
  }

  /* default */ String getAsset() {
    return asset;
  }

  /* default */ BigDecimal getFeeFraction() {
    return feeFraction;
  }

  /* default */ LocalDateTime getUpdated() {
    return updated;
  }
}
