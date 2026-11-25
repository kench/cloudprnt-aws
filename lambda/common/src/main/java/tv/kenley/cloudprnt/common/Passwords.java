package tv.kenley.cloudprnt.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Printer passwords: 24 random bytes as 32 base64url characters. Only a SHA-256 hash is stored; the
 * passwords are random, so a slow hash adds nothing.
 */
public final class Passwords {
  private static final int BYTES = 24;

  private Passwords() {}

  /** A new password. */
  public static String generate(SecureRandom random) {
    byte[] bytes = new byte[BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The stored form of {@code password}: SHA-256, in hex. */
  public static String hash(String password) {
    return HexFormat.of().formatHex(sha256(password));
  }

  /** Whether {@code password} matches {@code storedHash}, in constant time. */
  public static boolean matches(String password, String storedHash) {
    byte[] expected;
    try {
      expected = HexFormat.of().parseHex(storedHash);
    } catch (IllegalArgumentException e) {
      return false;
    }
    return MessageDigest.isEqual(sha256(password), expected);
  }

  private static byte[] sha256(String password) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
