/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules.impl.billing;

import com.google.common.base.Strings;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jp.co.soramitsu.iroha.java.detail.Const;

public class BillingInfo {

  private static final String DOMAIN_ATTRIBUTE = "domain";
  private static final String BILLING_TYPE_ATTRIBUTE = "billingType";
  private static final String ASSET_ATTRIBUTE = "asset";
  private static final String FEE_TYPE_ATTRIBUTE = "feeType";
  private static final String FEE_FRACTION_ATTRIBUTE = "feeFraction";
  private static final String CREATED_ATTRIBUTE = "created";
  private static final String UPDATED_ATTRIBUTE = "updated";

  private String domain;
  private BillingTypeEnum billingType;
  private String asset;
  private FeeTypeEnum feeType;
  private BigDecimal feeFraction;
  private long updated;

  private BillingInfo(
      String domain,
      BillingTypeEnum billingType,
      String asset,
      FeeTypeEnum feeType,
      BigDecimal feeFraction,
      long updated) {

    if (Strings.isNullOrEmpty(domain)) {
      throw new IllegalArgumentException("Domain must not be neither null nor empty");
    }
    Objects.requireNonNull(billingType, "Billing type must not be null");
    if (Strings.isNullOrEmpty(asset)) {
      throw new IllegalArgumentException("Asset must not be neither null nor empty");
    }
    Objects.requireNonNull(feeType, "Fee type must not be null");
    Objects.requireNonNull(feeFraction, "Fee fraction must not be null");

    this.domain = domain;
    this.billingType = billingType;
    this.asset = asset;
    this.feeType = feeType;
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

  public enum FeeTypeEnum {
    FIXED,
    FRACTION,
    UNKNOWN;

    /* default */ static FeeTypeEnum safeValueOf(String name) {
      try {
        return FeeTypeEnum.valueOf(name);
      } catch (IllegalArgumentException e) {
        return FeeTypeEnum.UNKNOWN;
      }
    }
  }

  /* default */ static Set<BillingInfo> parseBillingHttpDto(String billingType,
      Map<String, Map<String, JsonObject>> domainsMap) {

    final Set<BillingInfo> result = new HashSet<>();
    domainsMap.forEach(
        (domain, assetsMap) -> assetsMap.forEach(
            (asset, info) -> {
              result.add(
                  new BillingInfo(
                      domain,
                      BillingTypeEnum.valueOfLabel(billingType),
                      asset,
                      FeeTypeEnum.safeValueOf(info.get(FEE_TYPE_ATTRIBUTE).getAsString()),
                      info.get(FEE_FRACTION_ATTRIBUTE).getAsBigDecimal(),
                      info.get(CREATED_ATTRIBUTE).getAsLong()
                  )
              );
            }
        )
    );

    return result;
  }

  /* default */ static BillingInfo parseBillingMqDto(JsonObject object) {
    return new BillingInfo(
        object.get(DOMAIN_ATTRIBUTE).getAsString(),
        BillingTypeEnum.valueOfLabel(object.get(BILLING_TYPE_ATTRIBUTE).getAsString()),
        object.get(ASSET_ATTRIBUTE).getAsString(),
        FeeTypeEnum.safeValueOf(object.get(FEE_TYPE_ATTRIBUTE).getAsString()),
        object.get(FEE_FRACTION_ATTRIBUTE).getAsBigDecimal(),
        object.get(UPDATED_ATTRIBUTE).getAsLong()
    );
  }

  /* default */ static String getDomain(String accountId) {
    return accountId.split(Const.accountIdDelimiter)[1];
  }

  /* default */ static String getName(String accountId) {
    return accountId.split(Const.accountIdDelimiter)[0];
  }

  @Override
  public int hashCode() {
    return (domain + billingType.name() + asset).hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == null || !this.getClass().isAssignableFrom(other.getClass())) {
      return false;
    }
    if (other == this) {
      return true;
    }
    BillingInfo otherObj = (BillingInfo) other;
    return otherObj.asset.equals(this.asset)
        && otherObj.billingType.equals(this.billingType)
        && otherObj.domain.equals(this.domain);
  }

  @Override
  public String toString() {
    return "Domain=" + domain +
        ";Type=" + billingType.name() +
        ";Asset=" + asset +
        ";FeeType=" + feeType.name() +
        ";FeeFraction=" + feeFraction.toPlainString() +
        ";Updated=" + updated;
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

  /* default */ FeeTypeEnum getFeeType() {
    return feeType;
  }

  /* default */ BigDecimal getFeeFraction() {
    return feeFraction;
  }

  /* default */ long getUpdated() {
    return updated;
  }
}
