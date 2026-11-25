package tv.kenley.cloudprnt.jobs;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.smithy.java.core.error.ModeledException;
import tv.kenley.cloudprnt.api.model.CancelPrintJobInput;
import tv.kenley.cloudprnt.api.model.DeletePrinterInput;
import tv.kenley.cloudprnt.api.model.GetPrintJobInput;
import tv.kenley.cloudprnt.api.model.GetPrinterInput;
import tv.kenley.cloudprnt.api.model.InternalError;
import tv.kenley.cloudprnt.api.model.ListPrintJobsInput;
import tv.kenley.cloudprnt.api.model.ListPrintersInput;
import tv.kenley.cloudprnt.api.model.RegisterPrinterInput;
import tv.kenley.cloudprnt.api.model.ResetPrinterPasswordInput;
import tv.kenley.cloudprnt.api.model.ResetPrinterPasswordOutput;
import tv.kenley.cloudprnt.api.model.SubmitPrintJobInput;
import tv.kenley.cloudprnt.common.HttpRequest;
import tv.kenley.cloudprnt.common.HttpResponse;
import tv.kenley.cloudprnt.common.Json;
import tv.kenley.cloudprnt.common.LogLine;

/**
 * The print-job API under {@code /v1/printers}, in {@code restJson1}. API Gateway checks each
 * caller's SigV4 signature; every call writes a log line with the caller.
 */
final class JobApi {
  private static final String SEGMENT = "([^/]+)";

  /** A route: method, path pattern and operation name. */
  private record Route(String method, Pattern path, String operation) {
    Route(String method, String path, String operation) {
      this(method, Pattern.compile("^" + path + "$"), operation);
    }
  }

  private static final List<Route> ROUTES =
      List.of(
          new Route("POST", "/v1/printers", "RegisterPrinter"),
          new Route("GET", "/v1/printers", "ListPrinters"),
          new Route("GET", "/v1/printers/" + SEGMENT, "GetPrinter"),
          new Route("DELETE", "/v1/printers/" + SEGMENT, "DeletePrinter"),
          new Route("POST", "/v1/printers/" + SEGMENT + "/password", "ResetPrinterPassword"),
          new Route("POST", "/v1/printers/" + SEGMENT + "/jobs", "SubmitPrintJob"),
          new Route("GET", "/v1/printers/" + SEGMENT + "/jobs", "ListPrintJobs"),
          new Route("GET", "/v1/printers/" + SEGMENT + "/jobs/" + SEGMENT, "GetPrintJob"),
          new Route(
              "POST",
              "/v1/printers/" + SEGMENT + "/jobs/" + SEGMENT + "/cancel",
              "CancelPrintJob"));

  private final JobService service;
  private final Consumer<String> log;
  private final Consumer<String> errorLog;

  JobApi(JobService service, Consumer<String> log, Consumer<String> errorLog) {
    this.service = service;
    this.log = log;
    this.errorLog = errorLog;
  }

  /** Handles one request. Never throws. */
  HttpResponse handle(HttpRequest request) {
    for (Route route : ROUTES) {
      Matcher matcher = route.path().matcher(request.path());
      if (route.method().equals(request.method()) && matcher.matches()) {
        String[] labels = new String[matcher.groupCount()];
        for (int i = 0; i < labels.length; i++) {
          labels[i] =
              URLDecoder.decode(matcher.group(i + 1).replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        return call(route.operation(), labels, request);
      }
    }
    return Json.unknownOperation();
  }

  private HttpResponse call(String operation, String[] labels, HttpRequest request) {
    LogLine line =
        new LogLine()
            .put("operation", operation)
            .put("caller", request.caller())
            .put("requestId", request.requestId());
    HttpResponse response;
    String result = "ok";
    try {
      response = dispatch(operation, labels, request, line);
    } catch (ModeledException e) {
      result = Json.errorType(e);
      response = Json.error(e);
    } catch (RuntimeException e) {
      errorLog.accept(operation + " failed: " + e);
      result = "InternalError";
      response = Json.error(InternalError.builder().message("Internal error.").build());
    }
    log.accept(line.put("result", result).toJson());
    return response;
  }

  private HttpResponse dispatch(
      String operation, String[] labels, HttpRequest request, LogLine line) {
    byte[] body = request.body();
    Map<String, String> query = request.query();
    String mac = labels.length > 0 ? labels[0] : null;
    line.put("mac", mac);
    Integer maxResults = maxResults(query.get("maxResults"));
    return switch (operation) {
      case "RegisterPrinter" -> {
        RegisterPrinterInput in = Json.decode(body, RegisterPrinterInput.builder(), b -> {});
        line.put("mac", in.getMac());
        yield Json.ok(201, service.register(in.getMac(), in.getName()));
      }
      case "ListPrinters" -> {
        ListPrintersInput in =
            Json.bind(
                ListPrintersInput.builder(),
                b -> b.nextToken(query.get("nextToken")).maxResults(maxResults));
        yield Json.ok(200, service.listPrinters(in.getNextToken(), in.getMaxResults()));
      }
      case "GetPrinter" -> {
        GetPrinterInput in = Json.bind(GetPrinterInput.builder(), b -> b.mac(mac));
        yield Json.ok(200, service.getPrinter(in.getMac()));
      }
      case "DeletePrinter" -> {
        DeletePrinterInput in = Json.bind(DeletePrinterInput.builder(), b -> b.mac(mac));
        service.deletePrinter(in.getMac());
        yield Json.empty(204);
      }
      case "ResetPrinterPassword" -> {
        ResetPrinterPasswordInput in =
            Json.bind(ResetPrinterPasswordInput.builder(), b -> b.mac(mac));
        yield Json.ok(
            200,
            ResetPrinterPasswordOutput.builder()
                .password(service.resetPassword(in.getMac()))
                .build());
      }
      case "SubmitPrintJob" -> {
        SubmitPrintJobInput in = Json.decode(body, SubmitPrintJobInput.builder(), b -> b.mac(mac));
        var job = service.submit(in.getMac(), in.getMediaType(), in.getContent());
        line.put("jobId", job.getJobId());
        yield Json.ok(201, job);
      }
      case "ListPrintJobs" -> {
        ListPrintJobsInput in =
            Json.bind(
                ListPrintJobsInput.builder(),
                b -> b.mac(mac).nextToken(query.get("nextToken")).maxResults(maxResults));
        yield Json.ok(200, service.listJobs(in.getMac(), in.getNextToken(), in.getMaxResults()));
      }
      case "GetPrintJob" -> {
        line.put("jobId", labels[1]);
        GetPrintJobInput in =
            Json.bind(GetPrintJobInput.builder(), b -> b.mac(mac).jobId(labels[1]));
        yield Json.ok(200, service.getJob(in.getMac(), in.getJobId()));
      }
      default -> {
        line.put("jobId", labels[1]);
        CancelPrintJobInput in =
            Json.bind(CancelPrintJobInput.builder(), b -> b.mac(mac).jobId(labels[1]));
        yield Json.ok(200, service.cancel(in.getMac(), in.getJobId()));
      }
    };
  }

  /** The {@code maxResults} query parameter; a value that isn't a number fails validation. */
  private static Integer maxResults(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
