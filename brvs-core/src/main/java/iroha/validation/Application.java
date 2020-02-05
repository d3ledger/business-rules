/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package iroha.validation;

import iroha.validation.filter.CrossDomainFilter;
import iroha.validation.service.ValidationService;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import java.net.PortUnreachableException;
import java.net.URI;
import java.security.KeyPair;
import java.util.logging.LogManager;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

public class Application {

  static {
    LogManager.getLogManager().reset();
    SLF4JBridgeHandler.install();
  }

  private static final Logger logger = LoggerFactory.getLogger(Application.class);
  private static final String BRVS_PORT_BEAN_NAME = "brvsPort";
  private static final int DEFAULT_PORT = 8080;
  private static final String BASE_URI_FORMAT = "http://0.0.0.0:%d/brvs/rest";

  public static void main(String[] args) {
    try {
      if (args.length == 0) {
        throw new IllegalArgumentException("Context file path argument is not specified");
      }
      FileSystemXmlApplicationContext context = new FileSystemXmlApplicationContext(args[0]);
      context.getBean(ValidationService.class).verifyTransactions();
      establishHttpServer(context);
    } catch (Exception e) {
      logger.error("Failed to start BRVS", e);
      System.exit(1);
    }
  }

  private static void establishHttpServer(AbstractApplicationContext context) {
    final ResourceConfig resourceConfig = new ResourceConfig().packages("iroha.validation.rest");
    resourceConfig.register(new AbstractBinder() {
      @Override
      protected void configure() {
        bind(context.getBean(TransactionVerdictStorage.class)).to(TransactionVerdictStorage.class);
        bind(context.getBean(RegistrationProvider.class)).to(RegistrationProvider.class);
        bind(context.getBean(IrohaAPI.class)).to(IrohaAPI.class);
        bind(context.getBean(CacheProvider.class)).to(CacheProvider.class);
        bind(context.getBean("brvsAccountKeyPair", KeyPair.class)).to(KeyPair.class);
      }
    });
    resourceConfig.register(new CrossDomainFilter());
    resourceConfig.property(ServerProperties.OUTBOUND_CONTENT_LENGTH_BUFFER, 0);

    int port = getPort(context);
    logger.info("Going to establish HTTP server on port {}", port);
    GrizzlyHttpServerFactory
        .createHttpServer(URI.create(String.format(BASE_URI_FORMAT, port)), resourceConfig);
  }

  private static int getPort(AbstractApplicationContext context) {
    int portBean = DEFAULT_PORT;
    try {
      portBean = Integer.parseInt(context.getBean(BRVS_PORT_BEAN_NAME, String.class));
      if (portBean < 0 || portBean > 65535) {
        throw new PortUnreachableException("Got port out of range: " + portBean);
      }
    } catch (PortUnreachableException e) {
      logger.warn("Couldn't read the port. Reason: {}", e.getMessage());
    }
    return portBean;
  }
}
