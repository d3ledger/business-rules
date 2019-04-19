package iroha.validation.security;

import com.google.common.base.Strings;
import java.util.Objects;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.credentials.authenticator.Authenticator;
import org.pac4j.core.exception.CredentialsException;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.util.CommonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrvsAuthenticator implements Authenticator<UsernamePasswordCredentials> {

  private static final Logger logger = LoggerFactory.getLogger(BrvsAuthenticator.class);

  private final String correctUsername;

  private final String correctPassword;

  private BrvsAuthenticator(String correctUsername, String correctPassword) {
    this.correctUsername = correctUsername;
    this.correctPassword = correctPassword;
  }

  public static BrvsAuthenticator getInstance(String username, String password) {
    if (Strings.isNullOrEmpty(username)) {
      throw new IllegalArgumentException("Username must not be null nor empty");
    }
    if (Strings.isNullOrEmpty(password)) {
      throw new IllegalArgumentException("Password must not be null nor empty");
    }
    return new BrvsAuthenticator(username, password);
  }

  public static BrvsAuthenticator getInstance(UsernamePasswordCredentials credentials) {
    Objects.requireNonNull(credentials, "Credentials must not be null");
    return getInstance(credentials.getUsername(), credentials.getPassword());
  }

  @Override
  public void validate(UsernamePasswordCredentials credentials, WebContext context) {
    if (credentials == null) {
      logger.error("No credentials provided to the authenticator.");
      throw new CredentialsException("No credential");
    }
    String username = credentials.getUsername();
    String password = credentials.getPassword();
    if (CommonHelper.isBlank(username) || CommonHelper.isBlank(password)) {
      logger.error("Malformed credentials provided to the authenticator.");
      throw new CredentialsException("Username or password cannot be blank");
    }
    if (CommonHelper.areNotEquals(username, correctUsername)) {
      logger.error("Username : '" + username + "' is unknown.");
      throw new CredentialsException("Username : '" + username + "' is unknown.");
    }
    if (CommonHelper.areNotEquals(password, correctPassword)) {
      logger.error("Username : '" + username + "' provided invalid password.");
      throw new CredentialsException("Username : '" + username + "' provided invalid password.");
    }
    final CommonProfile profile = new CommonProfile();
    profile.setId(username);
    profile.addAttribute(Pac4jConstants.USERNAME, username);
    credentials.setUserProfile(profile);
  }
}
