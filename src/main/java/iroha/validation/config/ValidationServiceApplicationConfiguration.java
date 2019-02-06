package iroha.validation.config;

import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.validators.Validator;
import iroha.validation.validators.impl.SampleValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Collection;
import java.util.Collections;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Class representing initialization config for validator service
 */
@Configuration
public class ValidationServiceApplicationConfiguration {

  @Value("${credential.accountId}")
  private String accountId;

  @Value("${credential.pubkeyPath}")
  private String pubkeyPath;

  @Value("${credential.privkeyPath}")
  private String privkeyPath;

  @Value("${iroha.host}")
  private String host;

  @Value("${iroha.port}")
  private int port;

  @Bean
  public String accountId() {
    return accountId;
  }

  @Bean
  public KeyPair keyPair() throws IOException {
    String pubKey = new String(Files.readAllBytes(Paths.get(pubkeyPath)));
    String privKey = new String(Files.readAllBytes(Paths.get(privkeyPath)));
    return Utils.parseHexKeypair(pubKey, privKey);
  }

  @Bean
  public IrohaAPI irohaAPI() {
    return new IrohaAPI(host, port);
  }

  @Bean
  public Collection<Rule> rules() {
    return Collections.singletonList(new SampleRule());
  }

  @Bean
  public Collection<Validator> validators() {
    return Collections.singletonList(new SampleValidator(rules()));
  }
}
