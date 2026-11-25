package tv.kenley.cloudprnt.common;

import java.util.Optional;

/** Registered printers. */
public interface Printers {
  /** The printer with this MAC address. */
  Optional<PrinterRecord> get(String mac);

  /** Adds {@code printer}; false if one with its MAC address exists. */
  boolean create(PrinterRecord printer);

  /** Removes a printer; false if there was none. */
  boolean delete(String mac);

  /** Replaces a printer's password hash; false if there is no such printer. */
  boolean setPasswordHash(String mac, String passwordHash);

  /** Records a poll; false if there is no such printer. */
  boolean recordPoll(String mac, PollUpdate update);

  /** Up to {@code limit} printers after the one with MAC address {@code after}. */
  Page<PrinterRecord> list(int limit, Optional<String> after);
}
