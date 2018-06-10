package net.vit.apprunner;

@SuppressWarnings("serial")
public class UnresolvedConstantException extends RuntimeException {
  public UnresolvedConstantException() {
    super();
  }

  public UnresolvedConstantException(String message) {
    super(message);
  }

  public UnresolvedConstantException(String message, Throwable cause) {
    super(message, cause);
  }

  public UnresolvedConstantException(Throwable cause) {
    super(cause);
  }
}
