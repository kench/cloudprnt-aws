package tv.kenley.cloudprnt.printer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * HTTP Basic credentials (RFC 7617). The user name is a MAC address, which may itself contain
 * colons, so the password starts after the last colon; generated passwords never contain one.
 */
record BasicAuth(String user, String password) {

  /** The credentials in an {@code Authorization} header value, if it holds valid ones. */
  static Optional<BasicAuth> parse(String header) {
    if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
      return Optional.empty();
    }
    String decoded;
    try {
      decoded =
          new String(
              Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    int colon = decoded.lastIndexOf(':');
    if (colon < 0) {
      return Optional.empty();
    }
    return Optional.of(new BasicAuth(decoded.substring(0, colon), decoded.substring(colon + 1)));
  }
}
