package iroha.validation;

import iroha.validation.rest.AccountValidityChecker;
import iroha.validation.service.ValidationService;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import java.net.URI;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

public class Application {

  private static final String BASE_URI = "http://0.0.0.0:8080/brvs/rest";

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
        bind(context.getBean(ValidationService.class)).to(ValidationService.class);
        bind(context.getBean(TransactionVerdictStorage.class)).to(TransactionVerdictStorage.class);
        bind(context.getBean(AccountValidityChecker.class)).to(AccountValidityChecker.class);
      }
    });
    GrizzlyHttpServerFactory.createHttpServer(URI.create(BASE_URI), resourceConfig);
  }
}
