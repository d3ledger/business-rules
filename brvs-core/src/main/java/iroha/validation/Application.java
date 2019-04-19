package iroha.validation;

import iroha.validation.security.BrvsAuthenticator;
import iroha.validation.service.ValidationService;
import iroha.validation.transactions.provider.RegistrationProvider;
import iroha.validation.transactions.provider.impl.util.CacheProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import java.net.URI;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.pac4j.core.authorization.authorizer.IsAuthenticatedAuthorizer;
import org.pac4j.core.config.Config;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.http.client.direct.DirectBasicAuthClient;
import org.pac4j.jax.rs.features.JaxRsConfigProvider;
import org.pac4j.jax.rs.features.Pac4JSecurityFeature;
import org.pac4j.jax.rs.grizzly.features.GrizzlyJaxRsContextFactoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

public class Application {

  private static final Logger logger = LoggerFactory.getLogger(Application.class);
  private static final String BRVS_PORT_BEAN_NAME = "brvsPort";
  private static final int DEFAULT_PORT = 8080;
  private static final String BASE_URI_FORMAT = "http://0.0.0.0:%d/brvs/rest";

  public static void main(String[] args) {
    if (args.length == 0) {
      throw new IllegalArgumentException("Context file path argument is not specified");
    }
    FileSystemXmlApplicationContext context = new FileSystemXmlApplicationContext(args[0]);
    context.getBean(ValidationService.class).verifyTransactions();
    establishHttpServer(context);
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
      }
    });
    resourceConfig.property(ServerProperties.OUTBOUND_CONTENT_LENGTH_BUFFER, 0);

    Config securityConfig = getSecurityConfig(context.getBean(UsernamePasswordCredentials.class));
    resourceConfig
        .register(new JaxRsConfigProvider(securityConfig))
        .register(new GrizzlyJaxRsContextFactoryProvider())
        .register(new Pac4JSecurityFeature());

    int port = getPort(context);
    logger.info("Going to establish HTTP server on port " + port);
    GrizzlyHttpServerFactory
        .createHttpServer(URI.create(String.format(BASE_URI_FORMAT, port)), resourceConfig);
  }

  private static int getPort(AbstractApplicationContext context) {
    int portBean = DEFAULT_PORT;
    try {
      portBean = Integer.parseInt(context.getBean(BRVS_PORT_BEAN_NAME, String.class));
      if (portBean < 0 || portBean > 65535) {
        throw new Exception("Got port out of range: " + portBean);
      }
    } catch (Exception e) {
      logger.warn("Couldn't read the port. Reason: " + e.getMessage());
    }
    return portBean;
  }

  private static Config getSecurityConfig(UsernamePasswordCredentials credentials) {
    DirectBasicAuthClient basicAuthClient = new DirectBasicAuthClient(
        new BrvsAuthenticator(credentials)
    );
    Config config = new Config(basicAuthClient);
    config.addAuthorizer("brvs", new IsAuthenticatedAuthorizer());
    return config;
  }
}
