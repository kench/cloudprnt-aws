package tv.kenley.cloudprnt.jobs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tv.kenley.cloudprnt.common.HttpRequest;
import tv.kenley.cloudprnt.common.HttpResponse;
import tv.kenley.cloudprnt.common.InMemoryContents;
import tv.kenley.cloudprnt.common.InMemoryJobs;
import tv.kenley.cloudprnt.common.InMemoryPrinters;
import tv.kenley.cloudprnt.common.JobRecord;
import tv.kenley.cloudprnt.common.JobState;
import tv.kenley.cloudprnt.common.Passwords;

final class JobApiTest {
  private static final String MAC = "00:11:62:12:34:56";
  private static final String MAC_PATH = "00%3A11%3A62%3A12%3A34%3A56";
  private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
  private static final String CALLER = "arn:aws:iam::123456789012:role/pos";

  private final InMemoryPrinters printers = new InMemoryPrinters();
  private final InMemoryJobs jobs = new InMemoryJobs();
  private final InMemoryContents contents = new InMemoryContents();
  private final List<String> log = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private final JobApi api =
      new JobApi(
          new JobService(
              printers, jobs, contents, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom()),
          log::add,
          errors::add);

  private HttpResponse call(String method, String path, Map<String, String> query, String body) {
    return api.handle(
        new HttpRequest(
            method,
            path,
            query,
            Map.of(),
            body.getBytes(StandardCharsets.UTF_8),
            "192.0.2.1",
            "req-1",
            CALLER));
  }

  private HttpResponse call(String method, String path) {
    return call(method, path, Map.of(), "");
  }

  private static String text(HttpResponse response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  private static String field(String json, String name) {
    Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
    assertTrue(m.find(), json);
    return m.group(1);
  }

  private String register() {
    HttpResponse response =
        call(
            "POST",
            "/v1/printers",
            Map.of(),
            "{\"mac\": \"00:11:62:12:34:56\", \"name\": \"Front\"}");
    assertEquals(201, response.status());
    return field(text(response), "password");
  }

  private String submit(String body) {
    HttpResponse response = call("POST", "/v1/printers/" + MAC_PATH + "/jobs", Map.of(), body);
    assertEquals(201, response.status(), text(response));
    return field(text(response), "jobId");
  }

  @Test
  void registeringIssuesPasswordMatchingTheStoredHash() {
    String password = register();

    assertEquals(32, password.length());
    assertTrue(Passwords.matches(password, printers.get(MAC).orElseThrow().passwordHash()));
    assertEquals(
        "{\"operation\":\"RegisterPrinter\",\"caller\":\""
            + CALLER
            + "\",\"requestId\":\"req-1\","
            + "\"mac\":\"00:11:62:12:34:56\",\"result\":\"ok\"}",
        log.getFirst());
    assertFalse(log.getFirst().contains(password));
  }

  @Test
  void macAddressesAreStoredInLowerCase() {
    call("POST", "/v1/printers", Map.of(), "{\"mac\": \"00:11:62:AB:CD:EF\"}");

    assertTrue(printers.get("00:11:62:ab:cd:ef").isPresent());
    assertEquals(200, call("GET", "/v1/printers/00:11:62:AB:CD:EF").status());
  }

  @Test
  void registeringTwiceIs409() {
    register();
    HttpResponse response =
        call("POST", "/v1/printers", Map.of(), "{\"mac\": \"00:11:62:12:34:56\"}");

    assertEquals(409, response.status());
    assertEquals("PrinterExists", response.headers().get("X-Amzn-Errortype"));
  }

  @Test
  void getAndListPrinters() {
    register();
    call("POST", "/v1/printers", Map.of(), "{\"mac\": \"00:11:62:00:00:01\"}");

    String printer = text(call("GET", "/v1/printers/" + MAC_PATH));
    assertEquals(
        "{\"mac\":\"00:11:62:12:34:56\",\"createdAt\":\"2026-11-25T09:00:00Z\",\"name\":\"Front\"}",
        printer);

    String first = text(call("GET", "/v1/printers", Map.of("maxResults", "1"), ""));
    String token = field(first, "nextToken");
    String second =
        text(call("GET", "/v1/printers", Map.of("maxResults", "1", "nextToken", token), ""));
    assertTrue(first.contains("00:11:62:00:00:01"), first);
    assertTrue(second.contains(MAC), second);
    assertFalse(second.contains("nextToken"), second);
    assertTrue(text(call("GET", "/v1/printers")).contains(MAC));
  }

  @Test
  void deleteAndResetPassword() {
    String old = register();

    String password =
        field(text(call("POST", "/v1/printers/" + MAC_PATH + "/password")), "password");
    assertFalse(Passwords.matches(old, printers.get(MAC).orElseThrow().passwordHash()));
    assertTrue(Passwords.matches(password, printers.get(MAC).orElseThrow().passwordHash()));

    HttpResponse deleted = call("DELETE", "/v1/printers/" + MAC_PATH);
    assertEquals(204, deleted.status());
    assertEquals(0, deleted.body().length);
    assertEquals(404, call("DELETE", "/v1/printers/" + MAC_PATH).status());
    assertEquals(404, call("POST", "/v1/printers/" + MAC_PATH + "/password").status());
    assertEquals(404, call("GET", "/v1/printers/" + MAC_PATH).status());
  }

  @Test
  void submittedJobsAreStoredAndQueued() {
    register();

    String jobId = submit("{\"mediaType\": \"text/plain\", \"content\": \"SGVsbG8=\"}");

    JobRecord job = jobs.get(MAC, jobId).orElseThrow();
    assertEquals(JobState.QUEUED, job.state());
    assertEquals(5, job.size());
    assertArrayEquals(
        "Hello".getBytes(StandardCharsets.UTF_8), contents.get(job.contentKey()).orElseThrow());
    assertEquals("text/plain", contents.mediaType(job.contentKey()));
    assertEquals(
        "{\"jobId\":\""
            + jobId
            + "\",\"mac\":\"00:11:62:12:34:56\",\"mediaType\":\"text/plain\","
            + "\"size\":5,\"state\":\"QUEUED\",\"createdAt\":\"2026-11-25T09:00:00Z\","
            + "\"updatedAt\":\"2026-11-25T09:00:00Z\",\"attempts\":0}",
        text(call("GET", "/v1/printers/" + MAC_PATH + "/jobs/" + jobId)));
    assertTrue(log.getLast().contains("\"jobId\":\"" + jobId + "\""));
  }

  @Test
  void submittingForUnknownPrinterIs404() {
    HttpResponse response =
        call(
            "POST",
            "/v1/printers/" + MAC_PATH + "/jobs",
            Map.of(),
            "{\"mediaType\": \"text/plain\", \"content\": \"SGVsbG8=\"}");

    assertEquals(404, response.status());
    assertEquals("PrinterNotFound", response.headers().get("X-Amzn-Errortype"));
  }

  @Test
  void listJobsNewestFirstWithPages() {
    register();
    String a = submit("{\"mediaType\": \"text/plain\", \"content\": \"YQ==\"}");
    String b = submit("{\"mediaType\": \"text/plain\", \"content\": \"Yg==\"}");
    String newest = a.compareTo(b) > 0 ? a : b;

    String first =
        text(call("GET", "/v1/printers/" + MAC_PATH + "/jobs", Map.of("maxResults", "1"), ""));
    assertTrue(first.contains(newest), first);
    String token = field(first, "nextToken");
    String second =
        text(call("GET", "/v1/printers/" + MAC_PATH + "/jobs", Map.of("nextToken", token), ""));
    assertFalse(second.contains(newest), second);
  }

  @Test
  void cancelOnlyQueuedJobs() {
    register();
    String jobId = submit("{\"mediaType\": \"text/plain\", \"content\": \"YQ==\"}");
    String path = "/v1/printers/" + MAC_PATH + "/jobs/" + jobId + "/cancel";

    HttpResponse cancelled = call("POST", path);
    assertEquals(200, cancelled.status());
    assertTrue(text(cancelled).contains("\"state\":\"CANCELLED\""));

    HttpResponse again = call("POST", path);
    assertEquals(409, again.status());
    assertEquals("JobNotCancellable", again.headers().get("X-Amzn-Errortype"));

    HttpResponse missing =
        call("POST", "/v1/printers/" + MAC_PATH + "/jobs/j-01JD5VZ6R1H8P3S0W2Y4A6C8E0/cancel");
    assertEquals(404, missing.status());
    assertEquals(
        404,
        call("GET", "/v1/printers/" + MAC_PATH + "/jobs/j-01JD5VZ6R1H8P3S0W2Y4A6C8E0").status());
  }

  @Test
  void finishedJobsShowTheirResult() {
    register();
    String jobId = submit("{\"mediaType\": \"text/plain\", \"content\": \"YQ==\"}");
    jobs.transition(MAC, jobId, Set.of(JobState.QUEUED), JobState.PRINTED, "200 OK", true, NOW);

    String job = text(call("GET", "/v1/printers/" + MAC_PATH + "/jobs/" + jobId));
    assertTrue(job.contains("\"state\":\"PRINTED\""), job);
    assertTrue(job.contains("\"result\":\"200 OK\""), job);
    assertTrue(job.contains("\"attempts\":1"), job);
  }

  @Test
  void invalidInputIs400() {
    assertEquals(400, call("GET", "/v1/printers/not-a-mac").status());
    assertEquals(400, call("GET", "/v1/printers", Map.of("maxResults", "0"), "").status());
    assertEquals(400, call("GET", "/v1/printers", Map.of("maxResults", "lots"), "").status());
    assertEquals(400, call("GET", "/v1/printers", Map.of("nextToken", "!!"), "").status());
    assertEquals(400, call("POST", "/v1/printers", Map.of(), "{}").status());
    register();
    HttpResponse badType =
        call(
            "POST",
            "/v1/printers/" + MAC_PATH + "/jobs",
            Map.of(),
            "{\"mediaType\": \"Plain Text\", \"content\": \"YQ==\"}");
    assertEquals(400, badType.status());
    assertEquals("ValidationException", badType.headers().get("X-Amzn-Errortype"));
    assertEquals(400, call("GET", "/v1/printers/" + MAC_PATH + "/jobs/123").status());
  }

  @Test
  void unknownRoutesAre404() {
    assertEquals(404, call("PUT", "/v1/printers").status());
    assertEquals(404, call("GET", "/v2/printers").status());
    assertTrue(log.isEmpty());
  }

  @Test
  void unexpectedFailuresAre500() {
    JobApi broken =
        new JobApi(
            new JobService(
                printers,
                jobs,
                new tv.kenley.cloudprnt.common.Contents() {
                  @Override
                  public void put(String key, String mediaType, byte[] content) {
                    throw new IllegalStateException("S3 is down");
                  }

                  @Override
                  public java.util.Optional<byte[]> get(String key) {
                    return java.util.Optional.empty();
                  }
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SecureRandom()),
            log::add,
            errors::add);
    register();

    HttpResponse response =
        broken.handle(
            new HttpRequest(
                "POST",
                "/v1/printers/" + MAC_PATH + "/jobs",
                Map.of(),
                Map.of(),
                "{\"mediaType\": \"text/plain\", \"content\": \"YQ==\"}"
                    .getBytes(StandardCharsets.UTF_8),
                "ip",
                "req-2",
                CALLER));

    assertEquals(500, response.status());
    assertEquals(
        "SubmitPrintJob failed: java.lang.IllegalStateException: S3 is down", errors.getFirst());
    assertTrue(jobs.next(MAC).isEmpty());
  }
}
