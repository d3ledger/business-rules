package iroha.validation;

import iroha.validation.service.ValidationService;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.support.FileSystemXmlApplicationContext;

@ComponentScan("iroha.validation")
public class Application {

  public static void main(String[] args) {
    new FileSystemXmlApplicationContext(args[0])
        .getBean(ValidationService.class)
        .verifyTransactions();
  }
}
