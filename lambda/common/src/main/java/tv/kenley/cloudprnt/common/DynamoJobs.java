package tv.kenley.cloudprnt.common;

import static tv.kenley.cloudprnt.common.Items.instant;
import static tv.kenley.cloudprnt.common.Items.millis;
import static tv.kenley.cloudprnt.common.Items.num;
import static tv.kenley.cloudprnt.common.Items.number;
import static tv.kenley.cloudprnt.common.Items.str;
import static tv.kenley.cloudprnt.common.Items.string;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

/**
 * Jobs in DynamoDB, keyed by {@code mac} and {@code jobId}. Job IDs are ULIDs, so they sort by
 * submission time. While a job is pending its {@code queue} attribute holds the MAC address, which
 * puts it in the sparse {@code queue} index; finished jobs lose it and get an {@code expiresAt} for
 * the table's TTL.
 */
public final class DynamoJobs implements Jobs {
  /** The sparse index of pending jobs. */
  public static final String QUEUE_INDEX = "queue";

  private final DynamoDbClient db;
  private final String table;

  /** Jobs in {@code table}. */
  public DynamoJobs(DynamoDbClient db, String table) {
    this.db = db;
    this.table = table;
  }

  @Override
  public void create(JobRecord job) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put("mac", str(job.mac()));
    item.put("jobId", str(job.jobId()));
    item.put("mediaType", str(job.mediaType()));
    item.put("size", num(job.size()));
    item.put("state", str(job.state().name()));
    item.put("createdAt", millis(job.createdAt()));
    item.put("updatedAt", millis(job.updatedAt()));
    item.put("attempts", num(job.attempts()));
    if (job.state().pending()) {
      item.put("queue", str(job.mac()));
    }
    db.putItem(
        r -> r.tableName(table).item(item).conditionExpression("attribute_not_exists(jobId)"));
  }

  @Override
  public Optional<JobRecord> get(String mac, String jobId) {
    Map<String, AttributeValue> item =
        db.getItem(r -> r.tableName(table).key(key(mac, jobId)).consistentRead(true)).item();
    return item == null || item.isEmpty() ? Optional.empty() : Optional.of(read(item));
  }

  @Override
  public Optional<JobRecord> next(String mac) {
    QueryResponse response =
        db.query(r ->
            r.tableName(table)
                .indexName(QUEUE_INDEX)
                .keyConditionExpression("#queue = :mac")
                .expressionAttributeNames(Map.of("#queue", "queue"))
                .expressionAttributeValues(Map.of(":mac", str(mac)))
                .scanIndexForward(true)
                .limit(1));
    return response.items().stream().findFirst().map(DynamoJobs::read);
  }

  @Override
  public Page<JobRecord> list(String mac, int limit, Optional<String> after) {
    QueryRequest.Builder request =
        QueryRequest.builder()
            .tableName(table)
            .keyConditionExpression("mac = :mac")
            .expressionAttributeValues(Map.of(":mac", str(mac)))
            .scanIndexForward(false)
            .limit(limit);
    after.ifPresent(jobId -> request.exclusiveStartKey(key(mac, jobId)));
    QueryResponse response = db.query(request.build());
    List<JobRecord> jobs = response.items().stream().map(DynamoJobs::read).toList();
    Optional<String> last =
        response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
            ? Optional.of(response.lastEvaluatedKey().get("jobId").s())
            : Optional.empty();
    return new Page<>(jobs, last);
  }

  @Override
  public Optional<JobRecord> transition(
      String mac,
      String jobId,
      Set<JobState> from,
      JobState to,
      String result,
      boolean fetched,
      Instant now) {
    List<String> sets = new ArrayList<>(List.of("#state = :to", "updatedAt = :now"));
    Map<String, AttributeValue> values = new HashMap<>();
    values.put(":to", str(to.name()));
    values.put(":now", millis(now));
    if (result != null) {
      sets.add("#result = :result");
      values.put(":result", str(result));
    }
    String update;
    if (to.pending()) {
      sets.add("#queue = :mac");
      values.put(":mac", str(mac));
      update = "SET " + String.join(", ", sets);
    } else {
      sets.add("expiresAt = :expires");
      values.put(":expires", num(now.plus(RETENTION).getEpochSecond()));
      update = "SET " + String.join(", ", sets) + " REMOVE #queue";
    }
    if (fetched) {
      update += " ADD attempts :one";
      values.put(":one", num(1));
    }
    List<String> allowed = new ArrayList<>();
    for (JobState state : from) {
      allowed.add(":from" + state.ordinal());
      values.put(":from" + state.ordinal(), str(state.name()));
    }
    String condition = "attribute_exists(jobId) AND #state IN (" + String.join(", ", allowed) + ")";
    String expression = update;
    try {
      Map<String, AttributeValue> item =
          db.updateItem(r ->
                  r.tableName(table)
                      .key(key(mac, jobId))
                      .updateExpression(expression)
                      .conditionExpression(condition)
                      .expressionAttributeNames(
                          Map.of("#state", "state", "#result", "result", "#queue", "queue"))
                      .expressionAttributeValues(values)
                      .returnValues(ReturnValue.ALL_NEW))
              .attributes();
      return Optional.of(read(item));
    } catch (ConditionalCheckFailedException e) {
      return Optional.empty();
    }
  }

  private static Map<String, AttributeValue> key(String mac, String jobId) {
    return Map.of("mac", str(mac), "jobId", str(jobId));
  }

  static JobRecord read(Map<String, AttributeValue> item) {
    return new JobRecord(
        string(item, "mac"),
        string(item, "jobId"),
        string(item, "mediaType"),
        number(item, "size"),
        JobState.valueOf(string(item, "state")),
        instant(item, "createdAt"),
        instant(item, "updatedAt"),
        Math.toIntExact(number(item, "attempts")),
        string(item, "result"));
  }
}
