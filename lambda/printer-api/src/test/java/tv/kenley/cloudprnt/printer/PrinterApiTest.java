package tv.kenley.cloudprnt.printer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tv.kenley.cloudprnt.common.HttpRequest;
import tv.kenley.cloudprnt.common.HttpResponse;
import tv.kenley.cloudprnt.common.InMemoryContents;
import tv.kenley.cloudprnt.common.InMemoryJobs;
import tv.kenley.cloudprnt.common.InMemoryPrinters;
import tv.kenley.cloudprnt.common.JobRecord;
import tv.kenley.cloudprnt.common.JobState;
import tv.kenley.cloudprnt.common.Passwords;
import tv.kenley.cloudprnt.common.PrinterRecord;

final class PrinterApiTest {
  private static final String MAC = "00:11:62:12:34:56";
  private static final String PASSWORD = "secret-password";
  private static final String JOB = "j-01JD5VZ6R1H8P3S0W2Y4A6C8E0";
  private static final String LATER_JOB = "j-01JD5VZ6R1H8P3S0W2Y4A6C8E9";
  private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

  private final InMemoryPrinters printers = new InMemoryPrinters();
  private final InMemoryJobs jobs = new InMemoryJobs();
  private final InMemoryContents contents = new InMemoryContents();
  private final List<String> log = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private Instant now = NOW;
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };
  private final PrinterApi api =
      new PrinterApi(
          printers, new CloudPrntService(printers, jobs, contents, clock), log::add, errors::add);

  @BeforeEach
  void register() {
    printers.create(PrinterRecord.registered(MAC, "Front", Passwords.hash(PASSWORD), NOW));
  }

  private void knownClient() {
    printers.recordPoll(
        MAC,
        new tv.kenley.cloudprnt.common.PollUpdate(
            NOW, null, null, null, "mC-Print3", "5", 576, NOW));
  }

  private void queue(String jobId, String mediaType, byte[] content) {
    JobRecord job =
        new JobRecord(MAC, jobId, mediaType, content.length, JobState.QUEUED, NOW, NOW, 0, null);
    jobs.create(job);
    contents.put(job.contentKey(), mediaType, content);
  }

  private static String auth(String user, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static HttpRequest request(
      String method, Map<String, String> query, String body, String authorization) {
    Map<String, String> headers = new HashMap<>();
    if (authorization != null) {
      headers.put("authorization", authorization);
    }
    return new HttpRequest(
        method,
        PrinterApi.PATH,
        query,
        headers,
        body.getBytes(StandardCharsets.UTF_8),
        "192.0.2.1",
        "req-1",
        null);
  }

  private HttpResponse poll(String body) {
    return api.handle(request("POST", Map.of(), body, auth(MAC, PASSWORD)));
  }

  private HttpResponse poll() {
    return poll("{\"printerMAC\": \"" + MAC + "\", \"statusCode\": \"200%20OK\"}");
  }

  private HttpResponse fetch(Map<String, String> query) {
    return api.handle(request("GET", query, "", auth(MAC, PASSWORD)));
  }

  private HttpResponse confirm(Map<String, String> query) {
    return api.handle(request("DELETE", query, "", auth(MAC, PASSWORD)));
  }

  private static String text(HttpResponse response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  @Test
  void firstPollAsksForClientInfoAndRecordsStatus() {
    HttpResponse response =
        poll(
            "{\"printerMAC\": \"00:11:62:12:34:56\", \"status\": \"23 86\","
                + " \"statusCode\": \"200%20OK\", \"printingInProgress\": false,"
                + " \"somethingNew\": [1]}");

    assertEquals(200, response.status());
    assertEquals(
        "{\"jobReady\":false,\"clientAction\":[{\"request\":\"ClientType\",\"options\":\"\"},"
            + "{\"request\":\"ClientVersion\",\"options\":\"\"},"
            + "{\"request\":\"PageInfo\",\"options\":\"\"}]}",
        text(response));
    PrinterRecord printer = printers.get(MAC).orElseThrow();
    assertEquals("200 OK", printer.statusCode());
    assertEquals("23 86", printer.status());
    assertEquals(NOW, printer.lastPollAt());
    assertEquals(NOW, printer.infoRequestedAt());
    assertEquals(
        "{\"operation\":\"Poll\",\"requestId\":\"req-1\",\"sourceIp\":\"192.0.2.1\","
            + "\"mac\":\"00:11:62:12:34:56\",\"result\":\"clientInfoRequested\"}",
        log.getFirst());
  }

  @Test
  void clientActionResultsAreRecorded() {
    poll();
    now = NOW.plusSeconds(5);

    HttpResponse response =
        poll(
            "{\"printerMAC\": \"00:11:62:12:34:56\", \"clientAction\": ["
                + "{\"request\": \"ClientType\", \"result\": \"Star mC-Print3\"},"
                + "{\"request\": \"ClientVersion\", \"result\": 5.1},"
                + "{\"request\": \"PageInfo\", \"result\": {\"printWidth\": \"72\","
                + " \"horizontalResolution\": 8}},"
                + "{\"request\": \"Encodings\", \"result\": \"x\"}]}");

    assertEquals("{\"jobReady\":false}", text(response));
    PrinterRecord printer = printers.get(MAC).orElseThrow();
    assertEquals("Star mC-Print3", printer.clientType());
    assertEquals("5.1", printer.clientVersion());
    assertEquals(576, printer.printWidthDots());
  }

  @Test
  void oddClientActionResultsAreIgnored() {
    poll(
        "{\"printerMAC\": \"00:11:62:12:34:56\", \"clientAction\": ["
            + "{\"request\": \"ClientType\", \"result\": [1]},"
            + "{\"request\": \"ClientVersion\"},"
            + "{\"request\": \"PageInfo\", \"result\": {\"printWidth\": \"wide\","
            + " \"horizontalResolution\": 8}}]}");
    poll(
        "{\"printerMAC\": \"00:11:62:12:34:56\", \"clientAction\": ["
            + "{\"request\": \"PageInfo\", \"result\": \"72\"},"
            + "{\"request\": \"PageInfo\", \"result\": {\"printWidth\": \"72\"}}]}");

    PrinterRecord printer = printers.get(MAC).orElseThrow();
    assertNull(printer.clientType());
    assertNull(printer.clientVersion());
    assertNull(printer.printWidthDots());
  }

  @Test
  void clientInfoIsAskedForAgainOnlyAfterAnHour() {
    poll();
    now = NOW.plusSeconds(60);
    assertEquals("{\"jobReady\":false}", text(poll()));
    now = NOW.plus(CloudPrntService.INFO_RETRY).plusSeconds(1);
    assertTrue(text(poll()).contains("clientAction"));
  }

  @Test
  void queuedJobIsOfferedPrintedAndConfirmed() {
    knownClient();
    queue(JOB, "text/plain", "Hello".getBytes(StandardCharsets.UTF_8));

    assertEquals(
        "{\"jobReady\":true,\"mediaTypes\":[\"text/plain\"],\"jobToken\":\"" + JOB + "\"}",
        text(poll()));

    HttpResponse job = fetch(Map.of("mac", MAC, "type", "text/plain", "token", JOB));
    assertEquals(200, job.status());
    assertEquals("text/plain", job.headers().get("Content-Type"));
    assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8), job.body());
    assertEquals(JobState.SENT, jobs.get(MAC, JOB).orElseThrow().state());
    assertEquals(1, jobs.get(MAC, JOB).orElseThrow().attempts());

    HttpResponse confirmed = confirm(Map.of("mac", MAC, "code", "200 OK", "token", JOB));
    assertEquals(200, confirmed.status());
    assertEquals("{}", text(confirmed));
    JobRecord done = jobs.get(MAC, JOB).orElseThrow();
    assertEquals(JobState.PRINTED, done.state());
    assertEquals("200 OK", done.result());
    assertEquals("{\"jobReady\":false}", text(poll()));
  }

  @Test
  void withoutTokenTheOldestJobIsUsed() {
    knownClient();
    queue(LATER_JOB, "text/plain", new byte[] {2});
    queue(JOB, "text/plain", new byte[] {1});

    assertArrayEquals(new byte[] {1}, fetch(Map.of("mac", MAC, "type", "text/plain")).body());
    confirm(Map.of("mac", MAC, "code", "200 OK"));

    assertEquals(JobState.PRINTED, jobs.get(MAC, JOB).orElseThrow().state());
    assertEquals(JobState.QUEUED, jobs.get(MAC, LATER_JOB).orElseThrow().state());
  }

  @Test
  void downloadTimeoutQueuesTheJobAgainAndOtherErrorsFailIt() {
    knownClient();
    queue(JOB, "text/plain", new byte[] {1});
    queue(LATER_JOB, "text/plain", new byte[] {2});

    fetch(Map.of("mac", MAC, "type", "text/plain", "token", JOB));
    confirm(Map.of("mac", MAC, "code", "520 Download timeout", "token", JOB));
    assertEquals(JobState.QUEUED, jobs.get(MAC, JOB).orElseThrow().state());

    fetch(Map.of("mac", MAC, "type", "text/plain", "token", JOB));
    confirm(Map.of("mac", MAC, "code", "410 Gone", "token", JOB));
    JobRecord failed = jobs.get(MAC, JOB).orElseThrow();
    assertEquals(JobState.FAILED, failed.state());
    assertEquals("410 Gone", failed.result());
  }

  @Test
  void jobsFetchedTooOftenFail() {
    knownClient();
    queue(JOB, "text/plain", new byte[] {1});
    for (int i = 0; i < CloudPrntService.MAX_ATTEMPTS; i++) {
      fetch(Map.of("mac", MAC, "type", "text/plain", "token", JOB));
    }
    confirm(Map.of("mac", MAC, "code", "520 Download timeout", "token", JOB));
    assertEquals(JobState.FAILED, jobs.get(MAC, JOB).orElseThrow().state());

    JobRecord stuck =
        new JobRecord(
            MAC,
            LATER_JOB,
            "text/plain",
            1,
            JobState.SENT,
            NOW,
            NOW,
            CloudPrntService.MAX_ATTEMPTS,
            null);
    jobs.create(stuck);
    assertEquals("{\"jobReady\":false}", text(poll()));
    JobRecord failed = jobs.get(MAC, LATER_JOB).orElseThrow();
    assertEquals(JobState.FAILED, failed.state());
    assertEquals("Too many attempts", failed.result());
  }

  @Test
  void fetchingWithoutJobIs404() {
    knownClient();
    HttpResponse response = fetch(Map.of("mac", MAC, "type", "text/plain"));

    assertEquals(404, response.status());
    assertEquals("NoJob", response.headers().get("X-Amzn-Errortype"));
  }

  @Test
  void finishedJobsTokenFindsNothing() {
    knownClient();
    queue(JOB, "text/plain", new byte[] {1});
    jobs.transition(
        MAC, JOB, java.util.Set.of(JobState.QUEUED), JobState.CANCELLED, null, false, NOW);

    assertEquals(404, fetch(Map.of("mac", MAC, "type", "text/plain", "token", JOB)).status());
    assertEquals(200, confirm(Map.of("mac", MAC, "code", "200 OK", "token", JOB)).status());
    assertEquals(JobState.CANCELLED, jobs.get(MAC, JOB).orElseThrow().state());
  }

  @Test
  void otherMediaTypeIs415() {
    knownClient();
    queue(JOB, "image/png", new byte[] {1});

    HttpResponse response = fetch(Map.of("mac", MAC, "type", "text/plain", "token", JOB));

    assertEquals(415, response.status());
    assertTrue(text(response).contains("image/png"));
  }

  @Test
  void expiredContentFailsTheJob() {
    knownClient();
    queue(JOB, "text/plain", new byte[] {1});
    contents.expire(JobRecord.contentKey(MAC, JOB));

    assertEquals(404, fetch(Map.of("mac", MAC, "type", "text/plain")).status());
    assertEquals(JobState.FAILED, jobs.get(MAC, JOB).orElseThrow().state());
  }

  @Test
  void credentialsAreRequired() {
    HttpResponse none = api.handle(request("POST", Map.of(), "{}", null));
    assertEquals(401, none.status());
    assertEquals("Basic realm=\"CloudPRNT\"", none.headers().get("WWW-Authenticate"));

    assertEquals(401, api.handle(request("POST", Map.of(), "{}", auth(MAC, "wrong"))).status());
    assertEquals(
        401,
        api.handle(request("POST", Map.of(), "{}", auth("00:11:62:00:00:00", PASSWORD))).status());
    assertEquals(401, api.handle(request("POST", Map.of(), "{}", "Bearer x")).status());
    assertEquals(401, api.handle(request("POST", Map.of(), "{}", "Basic !!!")).status());
    String noColon = Base64.getEncoder().encodeToString("nocolon".getBytes(StandardCharsets.UTF_8));
    assertEquals(401, api.handle(request("POST", Map.of(), "{}", "Basic " + noColon)).status());
  }

  @Test
  void theUserNameMayBeWrittenWithoutColons() {
    HttpResponse response =
        api.handle(
            request(
                "POST",
                Map.of(),
                "{\"printerMAC\": \"00:11:62:12:34:56\"}",
                auth("001162123456", PASSWORD)));

    assertEquals(200, response.status());
    assertEquals(401, api.handle(request("POST", Map.of(), "{}", auth("nope", PASSWORD))).status());
  }

  @Test
  void theUserNameIsCaseInsensitive() {
    HttpResponse response =
        api.handle(
            request(
                "POST",
                Map.of(),
                "{\"printerMAC\": \"00:11:62:12:34:56\"}",
                auth(MAC.toUpperCase(java.util.Locale.ROOT), PASSWORD)));

    assertEquals(200, response.status());
  }

  @Test
  void credentialsMustMatchThePrinterInTheRequest() {
    assertEquals(401, poll("{\"printerMAC\": \"00:11:62:00:00:00\"}").status());
    assertEquals(401, fetch(Map.of("mac", "00:11:62:00:00:00", "type", "text/plain")).status());
  }

  @Test
  void invalidRequestsAre400() {
    HttpResponse response = poll("{\"printerMAC\": \"nope\"}");
    assertEquals(400, response.status());
    assertEquals("ValidationException", response.headers().get("X-Amzn-Errortype"));
    assertEquals(400, fetch(Map.of("mac", MAC)).status());
  }

  @Test
  void otherPathsAndMethodsAreUnknown() {
    HttpRequest put = request("PUT", Map.of(), "", auth(MAC, PASSWORD));
    assertEquals(404, api.handle(put).status());
    HttpRequest elsewhere =
        new HttpRequest("POST", "/other", Map.of(), Map.of(), new byte[0], "ip", "r", null);
    assertEquals(404, api.handle(elsewhere).status());
    assertTrue(log.isEmpty());
  }

  @Test
  void unexpectedFailuresAre500AndLogged() {
    tv.kenley.cloudprnt.common.Printers failing =
        org.mockito.Mockito.mock(tv.kenley.cloudprnt.common.Printers.class);
    org.mockito.Mockito.when(failing.get(MAC)).thenReturn(printers.get(MAC));
    org.mockito.Mockito.when(
            failing.recordPoll(
                org.mockito.ArgumentMatchers.eq(MAC), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("boom"));
    PrinterApi broken =
        new PrinterApi(
            failing, new CloudPrntService(failing, jobs, contents, clock), log::add, errors::add);

    HttpResponse response =
        broken.handle(
            request("POST", Map.of(), "{\"printerMAC\": \"" + MAC + "\"}", auth(MAC, PASSWORD)));

    assertEquals(500, response.status());
    assertEquals("Poll failed: java.lang.IllegalStateException: boom", errors.getFirst());
    assertTrue(log.getFirst().contains("\"result\":\"InternalError\""));
  }

  @Test
  void statusCodesThatArentUrlEncodingAreKept() {
    assertEquals("100%", CloudPrntService.decode("100%"));
    assertNull(CloudPrntService.decode(null));
    assertEquals(Duration.ofHours(1), CloudPrntService.INFO_RETRY);
  }
}
