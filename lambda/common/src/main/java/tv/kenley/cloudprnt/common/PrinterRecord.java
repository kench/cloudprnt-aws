package tv.kenley.cloudprnt.common;

import java.time.Instant;

/**
 * A registered printer.
 *
 * @param mac the MAC address, in lower case
 * @param name a display name, or {@code null}
 * @param passwordHash the password's SHA-256, in hex
 * @param createdAt when it was registered
 * @param lastPollAt when it last polled, or {@code null}
 * @param status the raw ASB status from the last poll, or {@code null}
 * @param statusCode the short status from the last poll, decoded, or {@code null}
 * @param printingInProgress whether it was printing at the last poll, or {@code null}
 * @param clientType from the {@code ClientType} client action, or {@code null}
 * @param clientVersion from the {@code ClientVersion} client action, or {@code null}
 * @param printWidthDots from the {@code PageInfo} client action, or {@code null}
 * @param infoRequestedAt when client information was last requested, or {@code null}
 */
public record PrinterRecord(
    String mac,
    String name,
    String passwordHash,
    Instant createdAt,
    Instant lastPollAt,
    String status,
    String statusCode,
    Boolean printingInProgress,
    String clientType,
    String clientVersion,
    Integer printWidthDots,
    Instant infoRequestedAt) {

  /** A newly registered printer. */
  public static PrinterRecord registered(
      String mac, String name, String passwordHash, Instant createdAt) {
    return new PrinterRecord(
        mac, name, passwordHash, createdAt, null, null, null, null, null, null, null, null);
  }
}
