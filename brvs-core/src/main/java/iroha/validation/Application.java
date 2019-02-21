package iroha.validation;

import iroha.validation.service.ValidationService;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.support.FileSystemXmlApplicationContext;

@ComponentScan("iroha.validation")
public class Application {

  public static void main(String[] args) {
    if (args.length == 0) {
      throw new IllegalArgumentException("Context file path argument is not specified");
    }
    new FileSystemXmlApplicationContext(args[0])
        .getBean(ValidationService.class)
        .verifyTransactions();
  }
}
