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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

final class DynamoPrintersTest {
  private static final String MAC = "00:11:62:12:34:56";
  private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

  private final DynamoDbClient db = mock(DynamoDbClient.class, CALLS_REAL_METHODS);
  private final DynamoPrinters printers = new DynamoPrinters(db, "printers");

  @Test
  void readsEveryAttribute() {
    Map<String, AttributeValue> item =
        Map.ofEntries(
            Map.entry("mac", AttributeValue.fromS(MAC)),
            Map.entry("name", AttributeValue.fromS("Front")),
            Map.entry("passwordHash", AttributeValue.fromS("ab")),
            Map.entry("createdAt", AttributeValue.fromN("1795597200000")),
            Map.entry("lastPollAt", AttributeValue.fromN("1795597205000")),
            Map.entry("status", AttributeValue.fromS("23 86")),
            Map.entry("statusCode", AttributeValue.fromS("200 OK")),
            Map.entry("printingInProgress", AttributeValue.fromBool(true)),
            Map.entry("clientType", AttributeValue.fromS("Star mC-Print3")),
            Map.entry("clientVersion", AttributeValue.fromS("5.1")),
            Map.entry("printWidthDots", AttributeValue.fromN("576")),
            Map.entry("infoRequestedAt", AttributeValue.fromN("1795597201000")));
    doReturn(GetItemResponse.builder().item(item).build())
        .when(db)
        .getItem(any(GetItemRequest.class));

    PrinterRecord printer = printers.get(MAC).orElseThrow();

    assertEquals(
        new PrinterRecord(
            MAC,
            "Front",
            "ab",
            NOW,
            NOW.plusSeconds(5),
            "23 86",
            "200 OK",
            true,
            "Star mC-Print3",
            "5.1",
            576,
            NOW.plusSeconds(1)),
        printer);
    ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
    verify(db).getItem(request.capture());
    assertEquals("printers", request.getValue().tableName());
    assertTrue(request.getValue().consistentRead());
  }

  @Test
  void missingPrinterIsEmpty() {
    doReturn(GetItemResponse.builder().build()).when(db).getItem(any(GetItemRequest.class));

    assertEquals(Optional.empty(), printers.get(MAC));
  }

  @Test
  void createIsConditional() {
    doReturn(PutItemResponse.builder().build()).when(db).putItem(any(PutItemRequest.class));

    assertTrue(printers.create(PrinterRecord.registered(MAC, "Front", "ab", NOW)));

    ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(db).putItem(request.capture());
    assertEquals("attribute_not_exists(mac)", request.getValue().conditionExpression());
    assertEquals("Front", request.getValue().item().get("name").s());
    assertEquals("1795597200000", request.getValue().item().get("createdAt").n());
  }

  @Test
  void createWithoutNameLeavesItOut() {
    doReturn(PutItemResponse.builder().build()).when(db).putItem(any(PutItemRequest.class));

    printers.create(PrinterRecord.registered(MAC, null, "ab", NOW));

    ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
    verify(db).putItem(request.capture());
    assertFalse(request.getValue().item().containsKey("name"));
  }

  @Test
  void createFailsWhenThePrinterExists() {
    doThrow(ConditionalCheckFailedException.builder().build())
        .when(db)
        .putItem(any(PutItemRequest.class));

    assertFalse(printers.create(PrinterRecord.registered(MAC, null, "ab", NOW)));
  }

  @Test
  void deleteAndSetPasswordNeedThePrinter() {
    doReturn(DeleteItemResponse.builder().build())
        .when(db)
        .deleteItem(any(DeleteItemRequest.class));
    doThrow(ConditionalCheckFailedException.builder().build())
        .when(db)
        .updateItem(any(UpdateItemRequest.class));

    assertTrue(printers.delete(MAC));
    assertFalse(printers.setPasswordHash(MAC, "cd"));
  }

  @Test
  void recordPollSetsOnlyWhatChanged() {
    doReturn(UpdateItemResponse.builder().build())
        .when(db)
        .updateItem(any(UpdateItemRequest.class));

    assertTrue(
        printers.recordPoll(
            MAC, new PollUpdate(NOW, "23 86", "200 OK", false, null, null, 576, NOW)));

    ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
    verify(db).updateItem(request.capture());
    assertEquals(
        "SET #lastPollAt = :lastPollAt, #status = :status, #statusCode = :statusCode,"
            + " #printingInProgress = :printingInProgress, #printWidthDots = :printWidthDots,"
            + " #infoRequestedAt = :infoRequestedAt",
        request.getValue().updateExpression());
    assertEquals("attribute_exists(mac)", request.getValue().conditionExpression());
    assertEquals("576", request.getValue().expressionAttributeValues().get(":printWidthDots").n());
  }

  @Test
  void recordPollWithClientInfo() {
    doReturn(UpdateItemResponse.builder().build())
        .when(db)
        .updateItem(any(UpdateItemRequest.class));

    printers.recordPoll(MAC, new PollUpdate(NOW, null, null, null, "TSP100IV", "4.0", null, null));

    ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
    verify(db).updateItem(request.capture());
    assertEquals(
        "SET #lastPollAt = :lastPollAt, #clientType = :clientType,"
            + " #clientVersion = :clientVersion",
        request.getValue().updateExpression());
  }

  @Test
  void listPagesFromTheLastKey() {
    doReturn(
            ScanResponse.builder()
                .items(
                    java.util.List.of(
                        Map.of(
                            "mac", AttributeValue.fromS(MAC),
                            "passwordHash", AttributeValue.fromS("ab"),
                            "createdAt", AttributeValue.fromN("1795597200000"))))
                .lastEvaluatedKey(Map.of("mac", AttributeValue.fromS(MAC)))
                .build())
        .when(db)
        .scan(any(ScanRequest.class));

    Page<PrinterRecord> page = printers.list(1, Optional.of("00:11:62:00:00:00"));

    assertEquals(MAC, page.items().getFirst().mac());
    assertEquals(Optional.of(MAC), page.last());
    ArgumentCaptor<ScanRequest> request = ArgumentCaptor.forClass(ScanRequest.class);
    verify(db).scan(request.capture());
    assertEquals(1, request.getValue().limit());
    assertEquals("00:11:62:00:00:00", request.getValue().exclusiveStartKey().get("mac").s());
  }

  @Test
  void theLastPageHasNoKey() {
    doReturn(ScanResponse.builder().items(java.util.List.of()).build())
        .when(db)
        .scan(any(ScanRequest.class));

    assertEquals(Optional.empty(), printers.list(10, Optional.empty()).last());
  }
}
