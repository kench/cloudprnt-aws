package tv.kenley.cloudprnt.common;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** MAC addresses are stored and compared in lower case, with colons: {@code 00:11:62:12:34:56}. */
public final class Macs {
  private static final Pattern BARE = Pattern.compile("[0-9a-f]{12}");

  private Macs() {}

  /** {@code mac} in the stored form. */
  public static String normalize(String mac) {
    return mac.toLowerCase(Locale.ROOT);
  }

  /**
   * A MAC address written with colons, hyphens or no separators, in the stored form, or empty if
   * {@code text} isn't one.
   */
  public static Optional<String> parse(String text) {
    String bare = text.toLowerCase(Locale.ROOT).replace(":", "").replace("-", "");
    if (!BARE.matcher(bare).matches()) {
      return Optional.empty();
    }
    StringBuilder mac = new StringBuilder(17);
    for (int i = 0; i < 12; i += 2) {
      if (i > 0) {
        mac.append(':');
      }
      mac.append(bare, i, i + 2);
    }
    return Optional.of(mac.toString());
  }
}
