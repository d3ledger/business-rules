package iroha.validation.config;

import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.SampleRule;
import iroha.validation.transactions.provider.TransactionProvider;
import iroha.validation.transactions.provider.impl.BasicTransactionProvider;
import iroha.validation.transactions.storage.TransactionVerdictStorage;
import iroha.validation.transactions.storage.impl.DummyMemoryTransactionVerdictStorage;
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
import org.springframework.context.annotation.PropertySource;

/**
 * Class representing initialization config for validator service
 */
@Configuration
@PropertySource("application.properties")
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
    String pubKey = new String(
        Files.readAllBytes(
            Paths.get(System.getProperty("user.dir") + "/testdata/keys", pubkeyPath)));
    String privKey = new String(
        Files.readAllBytes(
            Paths.get(System.getProperty("user.dir") + "/testdata/keys", privkeyPath)));
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

  @Bean
  public TransactionVerdictStorage transactionVerdictStorage() {
    return new DummyMemoryTransactionVerdictStorage();
  }

  @Bean
  public TransactionProvider transactionProvider() throws IOException {
    return new BasicTransactionProvider(irohaAPI(), accountId(), keyPair(), transactionVerdictStorage());
  }
}
