package tv.kenley.cloudprnt.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** One JSON log line, built field by field. Null values are left out. */
public final class LogLine {
  private final Map<String, String> fields = new LinkedHashMap<>();

  /** Sets {@code name} to {@code value}, unless {@code value} is null. */
  public LogLine put(String name, String value) {
    if (value != null) {
      fields.put(name, value);
    }
    return this;
  }

  /** The line as a JSON object. */
  public String toJson() {
    return fields.entrySet().stream()
        .map(e -> quote(e.getKey()) + ":" + quote(e.getValue()))
        .collect(Collectors.joining(",", "{", "}"));
  }

  /** {@code value} as a JSON string (RFC 8259 escaping). */
  static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
