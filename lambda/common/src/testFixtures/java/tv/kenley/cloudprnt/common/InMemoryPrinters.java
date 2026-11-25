package tv.kenley.cloudprnt.common;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** {@link Printers} in memory, for tests. */
public final class InMemoryPrinters implements Printers {
  private final Map<String, PrinterRecord> printers = new TreeMap<>();

  @Override
  public Optional<PrinterRecord> get(String mac) {
    return Optional.ofNullable(printers.get(mac));
  }

  @Override
  public boolean create(PrinterRecord printer) {
    return printers.putIfAbsent(printer.mac(), printer) == null;
  }

  @Override
  public boolean delete(String mac) {
    return printers.remove(mac) != null;
  }

  @Override
  public boolean setPasswordHash(String mac, String passwordHash) {
    PrinterRecord p = printers.get(mac);
    if (p == null) {
      return false;
    }
    printers.put(
        mac,
        new PrinterRecord(
            p.mac(),
            p.name(),
            passwordHash,
            p.createdAt(),
            p.lastPollAt(),
            p.status(),
            p.statusCode(),
            p.printingInProgress(),
            p.clientType(),
            p.clientVersion(),
            p.printWidthDots(),
            p.infoRequestedAt()));
    return true;
  }

  @Override
  public boolean recordPoll(String mac, PollUpdate u) {
    PrinterRecord p = printers.get(mac);
    if (p == null) {
      return false;
    }
    printers.put(
        mac,
        new PrinterRecord(
            p.mac(),
            p.name(),
            p.passwordHash(),
            p.createdAt(),
            u.at(),
            or(u.status(), p.status()),
            or(u.statusCode(), p.statusCode()),
            or(u.printingInProgress(), p.printingInProgress()),
            or(u.clientType(), p.clientType()),
            or(u.clientVersion(), p.clientVersion()),
            or(u.printWidthDots(), p.printWidthDots()),
            or(u.infoRequestedAt(), p.infoRequestedAt())));
    return true;
  }

  @Override
  public Page<PrinterRecord> list(int limit, Optional<String> after) {
    List<PrinterRecord> rest =
        printers.values().stream()
            .filter(p -> after.isEmpty() || p.mac().compareTo(after.get()) > 0)
            .toList();
    List<PrinterRecord> page = rest.stream().limit(limit).toList();
    return new Page<>(
        page, rest.size() > limit ? Optional.of(page.getLast().mac()) : Optional.empty());
  }

  private static <T> T or(T value, T fallback) {
    return value != null ? value : fallback;
  }
}
