package iroha.validation;

import iroha.validation.service.ValidationService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan("iroha.validation")
public class Application {

  public static void main(String[] args) {
    new AnnotationConfigApplicationContext(Application.class)
        .getBean(ValidationService.class)
        .verifyTransactions();
  }
}
