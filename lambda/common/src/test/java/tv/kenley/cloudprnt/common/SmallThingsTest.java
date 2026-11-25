package tv.kenley.cloudprnt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

final class SmallThingsTest {
  @Test
  void ulidsSortByTimeAndHaveThePrefix() {
    SecureRandom random = new SecureRandom();
    String a = Ulid.generate("j-", Instant.parse("2026-11-25T09:00:00Z"), random);
    String b = Ulid.generate("j-", Instant.parse("2026-11-25T09:00:01Z"), random);

    assertTrue(a.matches("^j-[0-9A-HJKMNP-TV-Z]{26}$"), a);
    assertTrue(a.compareTo(b) < 0);
  }

  @Test
  void ulidsEncodeTheTime() {
    // The first ten characters are the 48-bit millisecond time.
    assertEquals(
        "0000000001",
        Ulid.generate("", Instant.ofEpochMilli(1), new SecureRandom()).substring(0, 10));
  }

  @Test
  void passwordsMatchOnlyTheirHash() {
    String password = Passwords.generate(new SecureRandom());
    String hash = Passwords.hash(password);

    assertEquals(32, password.length());
    assertEquals(64, hash.length());
    assertTrue(Passwords.matches(password, hash));
    assertFalse(Passwords.matches(password + "x", hash));
    assertFalse(Passwords.matches(password, "not hex"));
    assertNotEquals(password, Passwords.generate(new SecureRandom()));
  }

  @Test
  void macsAreLowerCase() {
    assertEquals("00:11:62:ab:cd:ef", Macs.normalize("00:11:62:AB:CD:EF"));
    assertEquals("00:11:62:ab:cd:ef", Macs.parse("00-11-62-AB-CD-EF").orElseThrow());
    assertEquals("00:11:62:ab:cd:ef", Macs.parse("001162abcdef").orElseThrow());
    assertEquals("00:11:62:ab:cd:ef", Macs.parse("00:11:62:ab:cd:ef").orElseThrow());
    assertTrue(Macs.parse("00:11:62:ab:cd").isEmpty());
    assertTrue(Macs.parse("00:11:62:ab:cd:eg").isEmpty());
  }

  @Test
  void logLinesAreJsonWithoutNulls() {
    String line =
        new LogLine().put("a", "x\"y\\z\n\r\t\u0001").put("b", null).put("c", "1").toJson();

    assertEquals("{\"a\":\"x\\\"y\\\\z\\n\\r\\t\\u0001\",\"c\":\"1\"}", line);
  }

  @Test
  void jobStates() {
    assertTrue(JobState.QUEUED.pending());
    assertTrue(JobState.SENT.pending());
    assertFalse(JobState.PRINTED.pending());
    assertEquals("jobs/00:11:62:12:34:56/j-1", JobRecord.contentKey("00:11:62:12:34:56", "j-1"));
  }

  @Test
  void settingsComeFromTheEnvironment() {
    Settings settings =
        Settings.from(Map.of("PRINTERS_TABLE", "p", "JOBS_TABLE", "j", "CONTENT_BUCKET", "b"));

    assertEquals(new Settings("p", "j", "b"), settings);
    assertTrue(settings.printers(mock(DynamoDbClient.class)) instanceof DynamoPrinters);
    assertTrue(settings.jobs(mock(DynamoDbClient.class)) instanceof DynamoJobs);
    assertTrue(settings.contents(mock(S3Client.class)) instanceof S3Contents);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Settings.from(Map.of("PRINTERS_TABLE", "p", "JOBS_TABLE", " ")));
    assertEquals("JOBS_TABLE must be set", e.getMessage());
  }
}
