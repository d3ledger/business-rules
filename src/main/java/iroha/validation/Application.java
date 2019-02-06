package iroha.validation;

import iroha.validation.service.ValidationService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class Application {

  public static void main(String[] args) {
    ConfigurableApplicationContext applicationContext = SpringApplication
        .run(Application.class, args);
    applicationContext.getBean(ValidationService.class).verifyTransactions();
  }
}
