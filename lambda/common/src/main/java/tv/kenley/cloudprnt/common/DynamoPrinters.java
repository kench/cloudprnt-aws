package tv.kenley.cloudprnt.common;

import static tv.kenley.cloudprnt.common.Items.bool;
import static tv.kenley.cloudprnt.common.Items.instant;
import static tv.kenley.cloudprnt.common.Items.millis;
import static tv.kenley.cloudprnt.common.Items.num;
import static tv.kenley.cloudprnt.common.Items.number;
import static tv.kenley.cloudprnt.common.Items.str;
import static tv.kenley.cloudprnt.common.Items.string;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * Printers in DynamoDB, one item per printer keyed by {@code mac}. Times are epoch milliseconds.
 */
public final class DynamoPrinters implements Printers {
  private static final String EXISTS = "attribute_exists(mac)";

  private final DynamoDbClient db;
  private final String table;

  /** Printers in {@code table}. */
  public DynamoPrinters(DynamoDbClient db, String table) {
    this.db = db;
    this.table = table;
  }

  @Override
  public Optional<PrinterRecord> get(String mac) {
    Map<String, AttributeValue> item =
        db.getItem(r -> r.tableName(table).key(key(mac)).consistentRead(true)).item();
    return item == null || item.isEmpty() ? Optional.empty() : Optional.of(read(item));
  }

  @Override
  public boolean create(PrinterRecord printer) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put("mac", str(printer.mac()));
    item.put("passwordHash", str(printer.passwordHash()));
    item.put("createdAt", millis(printer.createdAt()));
    if (printer.name() != null) {
      item.put("name", str(printer.name()));
    }
    return conditional(() ->
        db.putItem(
            r -> r.tableName(table).item(item).conditionExpression("attribute_not_exists(mac)")));
  }

  @Override
  public boolean delete(String mac) {
    return conditional(
        () -> db.deleteItem(r -> r.tableName(table).key(key(mac)).conditionExpression(EXISTS)));
  }

  @Override
  public boolean setPasswordHash(String mac, String passwordHash) {
    return conditional(() ->
        db.updateItem(r ->
            r.tableName(table)
                .key(key(mac))
                .updateExpression("SET passwordHash = :h")
                .conditionExpression(EXISTS)
                .expressionAttributeValues(Map.of(":h", str(passwordHash)))));
  }

  @Override
  public boolean recordPoll(String mac, PollUpdate update) {
    List<String> sets = new ArrayList<>();
    Map<String, AttributeValue> values = new HashMap<>();
    set(sets, values, "lastPollAt", millis(update.at()));
    if (update.status() != null) {
      set(sets, values, "status", str(update.status()));
    }
    if (update.statusCode() != null) {
      set(sets, values, "statusCode", str(update.statusCode()));
    }
    if (update.printingInProgress() != null) {
      set(sets, values, "printingInProgress", AttributeValue.fromBool(update.printingInProgress()));
    }
    if (update.clientType() != null) {
      set(sets, values, "clientType", str(update.clientType()));
    }
    if (update.clientVersion() != null) {
      set(sets, values, "clientVersion", str(update.clientVersion()));
    }
    if (update.printWidthDots() != null) {
      set(sets, values, "printWidthDots", num(update.printWidthDots()));
    }
    if (update.infoRequestedAt() != null) {
      set(sets, values, "infoRequestedAt", millis(update.infoRequestedAt()));
    }
    Map<String, String> names = new HashMap<>();
    for (String set : sets) {
      String name = set.substring(1, set.indexOf(' '));
      names.put("#" + name, name);
    }
    return conditional(() ->
        db.updateItem(r ->
            r.tableName(table)
                .key(key(mac))
                .updateExpression("SET " + String.join(", ", sets))
                .conditionExpression(EXISTS)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)));
  }

  @Override
  public Page<PrinterRecord> list(int limit, Optional<String> after) {
    ScanRequest.Builder request = ScanRequest.builder().tableName(table).limit(limit);
    after.ifPresent(mac -> request.exclusiveStartKey(key(mac)));
    ScanResponse response = db.scan(request.build());
    List<PrinterRecord> printers = response.items().stream().map(DynamoPrinters::read).toList();
    Optional<String> last =
        response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
            ? Optional.of(response.lastEvaluatedKey().get("mac").s())
            : Optional.empty();
    return new Page<>(printers, last);
  }

  private static void set(
      List<String> sets, Map<String, AttributeValue> values, String name, AttributeValue value) {
    sets.add("#" + name + " = :" + name);
    values.put(":" + name, value);
  }

  private static Map<String, AttributeValue> key(String mac) {
    return Map.of("mac", str(mac));
  }

  private static boolean conditional(Runnable write) {
    try {
      write.run();
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  static PrinterRecord read(Map<String, AttributeValue> item) {
    Long width = number(item, "printWidthDots");
    return new PrinterRecord(
        string(item, "mac"),
        string(item, "name"),
        string(item, "passwordHash"),
        instant(item, "createdAt"),
        instant(item, "lastPollAt"),
        string(item, "status"),
        string(item, "statusCode"),
        bool(item, "printingInProgress"),
        string(item, "clientType"),
        string(item, "clientVersion"),
        width == null ? null : Math.toIntExact(width),
        instant(item, "infoRequestedAt"));
  }
}
