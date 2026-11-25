package tv.kenley.cloudprnt.common;

import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Reading and writing DynamoDB attribute values. */
final class Items {
  private Items() {}

  static AttributeValue str(String value) {
    return AttributeValue.fromS(value);
  }

  static AttributeValue num(long value) {
    return AttributeValue.fromN(Long.toString(value));
  }

  static AttributeValue millis(Instant value) {
    return num(value.toEpochMilli());
  }

  static String string(Map<String, AttributeValue> item, String name) {
    AttributeValue value = item.get(name);
    return value == null ? null : value.s();
  }

  static Long number(Map<String, AttributeValue> item, String name) {
    AttributeValue value = item.get(name);
    return value == null ? null : Long.valueOf(value.n());
  }

  static Instant instant(Map<String, AttributeValue> item, String name) {
    Long value = number(item, name);
    return value == null ? null : Instant.ofEpochMilli(value);
  }

  static Boolean bool(Map<String, AttributeValue> item, String name) {
    AttributeValue value = item.get(name);
    return value == null ? null : value.bool();
  }
}
