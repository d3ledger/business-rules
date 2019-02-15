package iroha.validation.verdict;

public class ValidationResult {

  public static ValidationResult PENDING = new ValidationResult(Verdict.PENDING);
  public static ValidationResult VALIDATED = new ValidationResult(Verdict.VALIDATED);
  public static ValidationResult REJECTED(String reason) {
    return new ValidationResult(Verdict.REJECTED, reason);
  }

  private final Verdict status;

  private final String reason;

  private ValidationResult(Verdict status) {
    this(status, "");
  }

  private ValidationResult(Verdict status, String reason) {
    this.status = status;
    this.reason = reason;
  }

  public Verdict getStatus() {
    return status;
  }

  public String getReason() {
    return reason;
  }
}
