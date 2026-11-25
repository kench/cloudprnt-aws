package tv.kenley.cloudprnt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

final class DynamoJobsTest {
  private static final String MAC = "00:11:62:12:34:56";
  private static final String JOB = "j-01JD5VZ6R1H8P3S0W2Y4A6C8E0";
  private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
  private static final JobRecord QUEUED =
      new JobRecord(MAC, JOB, "text/plain", 14, JobState.QUEUED, NOW, NOW, 0, null);

  private final DynamoDbClient db = mock(DynamoDbClient.class, CALLS_REAL_METHODS);
  private final DynamoJobs jobs = new DynamoJobs(db, "jobs");

  private static Map<String, AttributeValue> item(JobState state, String result) {
    Map<String, AttributeValue> item =
        new java.util.HashMap<>(
            Map.of(
                "mac", AttributeValue.fromS(MAC),
                "jobId", AttributeValue.fromS(JOB),
                "mediaType", AttributeValue.fromS("text/plain"),
                "size", AttributeValue.fromN("14"),
                "state", AttributeValue.fromS(state.name()),
                "createdAt", AttributeValue.fromN("1795597200000"),
                "updatedAt", AttributeValue.fromN("1795597200000"),
                "attempts", AttributeValue.fromN("0")));
    if (result != null) {
      item.put("result", AttributeValue.fromS(result));
    }
    return item;
  }

  @Test
  void createPutsPendingJobsInTheQueue() {
    doReturn(PutItemResponse.builder().build()).when(db).putItem(any(PutItemRequest.class));

    jobs.create(QUEUED);

    ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(db).putItem(request.capture());
    assertEquals(MAC, request.getValue().item().get("queue").s());
    assertEquals("QUEUED", request.getValue().item().get("state").s());
    assertEquals("attribute_not_exists(jobId)", request.getValue().conditionExpression());
  }

  @Test
  void createLeavesFinishedJobsOutOfTheQueue() {
    doReturn(PutItemResponse.builder().build()).when(db).putItem(any(PutItemRequest.class));

    jobs.create(new JobRecord(MAC, JOB, "text/plain", 14, JobState.FAILED, NOW, NOW, 0, null));

    ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(db).putItem(request.capture());
    assertFalse(request.getValue().item().containsKey("queue"));
  }

  @Test
  void getReadsTheJob() {
    doReturn(GetItemResponse.builder().item(item(JobState.QUEUED, null)).build())
        .when(db)
        .getItem(any(GetItemRequest.class));

    assertEquals(Optional.of(QUEUED), jobs.get(MAC, JOB));
  }

  @Test
  void getOfMissingJobIsEmpty() {
    doReturn(GetItemResponse.builder().build()).when(db).getItem(any(GetItemRequest.class));

    assertEquals(Optional.empty(), jobs.get(MAC, JOB));
  }

  @Test
  void nextQueriesTheQueueIndexOldestFirst() {
    doReturn(QueryResponse.builder().items(List.of(item(JobState.QUEUED, null))).build())
        .when(db)
        .query(any(QueryRequest.class));

    assertEquals(Optional.of(QUEUED), jobs.next(MAC));

    ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
    verify(db).query(request.capture());
    assertEquals(DynamoJobs.QUEUE_INDEX, request.getValue().indexName());
    assertTrue(request.getValue().scanIndexForward());
    assertEquals(1, request.getValue().limit());
  }

  @Test
  void listIsNewestFirstAndPages() {
    doReturn(
            QueryResponse.builder()
                .items(List.of(item(JobState.PRINTED, "200 OK")))
                .lastEvaluatedKey(
                    Map.of("mac", AttributeValue.fromS(MAC), "jobId", AttributeValue.fromS(JOB)))
                .build())
        .when(db)
        .query(any(QueryRequest.class));

    Page<JobRecord> page = jobs.list(MAC, 1, Optional.of("j-01JD5VZ6R1H8P3S0W2Y4A6C8E9"));

    assertEquals("200 OK", page.items().getFirst().result());
    assertEquals(Optional.of(JOB), page.last());
    ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
    verify(db).query(request.capture());
    assertFalse(request.getValue().scanIndexForward());
    assertEquals(
        "j-01JD5VZ6R1H8P3S0W2Y4A6C8E9", request.getValue().exclusiveStartKey().get("jobId").s());
  }

  @Test
  void listWithoutMore() {
    doReturn(QueryResponse.builder().items(List.of()).build())
        .when(db)
        .query(any(QueryRequest.class));

    assertEquals(Optional.empty(), jobs.list(MAC, 10, Optional.empty()).last());
  }

  @Test
  void finishingJobTakesItOffTheQueueAndSetsItsExpiry() {
    doReturn(UpdateItemResponse.builder().attributes(item(JobState.PRINTED, "200 OK")).build())
        .when(db)
        .updateItem(any(UpdateItemRequest.class));

    JobRecord job =
        jobs.transition(MAC, JOB, Set.of(JobState.SENT), JobState.PRINTED, "200 OK", false, NOW)
            .orElseThrow();

    assertEquals(JobState.PRINTED, job.state());
    ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
    verify(db).updateItem(request.capture());
    UpdateItemRequest r = request.getValue();
    assertEquals(
        "SET #state = :to, updatedAt = :now, #result = :result, expiresAt = :expires"
            + " REMOVE #queue",
        r.updateExpression());
    assertEquals("attribute_exists(jobId) AND #state IN (:from1)", r.conditionExpression());
    assertEquals(
        Long.toString(NOW.plus(Jobs.RETENTION).getEpochSecond()),
        r.expressionAttributeValues().get(":expires").n());
  }

  @Test
  void fetchingJobCountsAnAttempt() {
    doReturn(UpdateItemResponse.builder().attributes(item(JobState.SENT, null)).build())
        .when(db)
        .updateItem(any(UpdateItemRequest.class));

    jobs.transition(MAC, JOB, Set.of(JobState.QUEUED), JobState.SENT, null, true, NOW);

    ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
    verify(db).updateItem(request.capture());
    assertEquals(
        "SET #state = :to, updatedAt = :now, #queue = :mac ADD attempts :one",
        request.getValue().updateExpression());
  }

  @Test
  void transitionFromWrongStateIsEmpty() {
    doThrow(ConditionalCheckFailedException.builder().build())
        .when(db)
        .updateItem(any(UpdateItemRequest.class));

    assertEquals(
        Optional.empty(),
        jobs.transition(MAC, JOB, Set.of(JobState.QUEUED), JobState.CANCELLED, null, false, NOW));
  }
}
