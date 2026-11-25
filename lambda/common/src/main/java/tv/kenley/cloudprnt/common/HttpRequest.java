package tv.kenley.cloudprnt.common;

import java.util.Map;
import java.util.Optional;

/**
 * An HTTP request, as far as the Lambdas need it.
 *
 * @param method the HTTP method
 * @param path the raw (URL-encoded) path
 * @param query the decoded query parameters
 * @param headers the headers, with lower-case names
 * @param body the body bytes, empty if there was none
 * @param sourceIp the caller's address
 * @param requestId the Lambda request ID
 * @param caller the IAM principal that signed the request, or {@code null}
 */
public record HttpRequest(
    String method,
    String path,
    Map<String, String> query,
    Map<String, String> headers,
    byte[] body,
    String sourceIp,
    String requestId,
    String caller) {

  /** The header named {@code name} (any case). */
  public Optional<String> header(String name) {
    return Optional.ofNullable(headers.get(name.toLowerCase(java.util.Locale.ROOT)));
  }
}
