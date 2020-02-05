/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation.rules;

import groovy.lang.GroovyClassLoader;
import org.springframework.util.StringUtils;

/**
 * Rule Groovy parser
 */
final class RuleParser {

  private static final GroovyClassLoader groovyClassLoader = new GroovyClassLoader();

  private RuleParser() {
    throw new IllegalStateException("Rule Parser cannot be instantiated");
  }

  /**
   * Parses given Groovy script
   *
   * @param script - groovy script to parse
   * @return parsed {@link Rule} instance
   * @throws IllegalArgumentException if script is empty or script class doesn't implement Rule or
   * in case of invalid(not compilable) script
   */
  public static Rule parse(String script) {
    if (StringUtils.isEmpty(script)) {
      throw new IllegalArgumentException("Cannot parse empty script");
    }
    Class scriptClass;
    Rule instance;
    try {
      scriptClass = groovyClassLoader.parseClass(script);
      instance = (Rule) scriptClass.newInstance();
      if (instance == null) {
        throw new NullPointerException("Rule instance cannot be null");
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid Rule script", e);
    }
    return instance;
  }
}
