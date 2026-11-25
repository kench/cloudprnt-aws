package tv.kenley.cloudprnt.printer;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import software.amazon.smithy.java.core.error.ModeledException;
import tv.kenley.cloudprnt.common.HttpRequest;
import tv.kenley.cloudprnt.common.HttpResponse;
import tv.kenley.cloudprnt.common.Json;
import tv.kenley.cloudprnt.common.LogLine;
import tv.kenley.cloudprnt.common.Macs;
import tv.kenley.cloudprnt.common.Passwords;
import tv.kenley.cloudprnt.common.PrinterRecord;
import tv.kenley.cloudprnt.common.Printers;
import tv.kenley.cloudprnt.protocol.model.ConfirmJobInput;
import tv.kenley.cloudprnt.protocol.model.ConfirmJobOutput;
import tv.kenley.cloudprnt.protocol.model.GetJobInput;
import tv.kenley.cloudprnt.protocol.model.GetJobOutput;
import tv.kenley.cloudprnt.protocol.model.InternalError;
import tv.kenley.cloudprnt.protocol.model.PollInput;
import tv.kenley.cloudprnt.protocol.model.Unauthorized;

/**
 * The CloudPRNT endpoint, {@code /cloudprnt}: {@code POST} polls, {@code GET} fetches a job and
 * {@code DELETE} confirms it. Every request must carry the printer's HTTP Basic credentials, and
 * writes one log line.
 */
final class PrinterApi {
  static final String PATH = "/cloudprnt";

  private static final Map<String, String> CHALLENGE =
      Map.of("WWW-Authenticate", "Basic realm=\"CloudPRNT\"");

  private final Printers printers;
  private final CloudPrntService service;
  private final Consumer<String> log;
  private final Consumer<String> errorLog;

  PrinterApi(
      Printers printers,
      CloudPrntService service,
      Consumer<String> log,
      Consumer<String> errorLog) {
    this.printers = printers;
    this.service = service;
    this.log = log;
    this.errorLog = errorLog;
  }

  /** Handles one request. Never throws. */
  HttpResponse handle(HttpRequest request) {
    String operation = operation(request);
    if (operation == null) {
      return Json.unknownOperation();
    }
    Outcome outcome = new Outcome();
    LogLine line =
        new LogLine()
            .put("operation", operation)
            .put("requestId", request.requestId())
            .put("sourceIp", request.sourceIp());
    HttpResponse response;
    try {
      PrinterRecord printer = authenticate(request);
      line.put("mac", printer.mac());
      response =
          switch (operation) {
            case "Poll" -> poll(printer, request, outcome);
            case "GetJob" -> getJob(printer, request, outcome);
            default -> confirm(printer, request, outcome);
          };
    } catch (Unauthorized e) {
      outcome.result("Unauthorized");
      response = Json.error(e, CHALLENGE);
    } catch (ModeledException e) {
      outcome.result(Json.errorType(e));
      response = Json.error(e);
    } catch (RuntimeException e) {
      errorLog.accept(operation + " failed: " + e);
      outcome.result("InternalError");
      response = Json.error(InternalError.builder().message("Internal error.").build());
    }
    log.accept(line.put("result", outcome.result()).put("jobId", outcome.jobId()).toJson());
    return response;
  }

  /** The operation for the request's method, or {@code null} if it isn't one. */
  private static String operation(HttpRequest request) {
    if (!PATH.equals(request.path())) {
      return null;
    }
    return switch (request.method()) {
      case "POST" -> "Poll";
      case "GET" -> "GetJob";
      case "DELETE" -> "ConfirmJob";
      default -> null;
    };
  }

  private HttpResponse poll(PrinterRecord printer, HttpRequest request, Outcome outcome) {
    PollInput input = Json.decode(request.body(), PollInput.builder(), b -> {});
    requireSame(printer, input.getPrinterMAC());
    return Json.ok(200, service.poll(printer, input, outcome));
  }

  private HttpResponse getJob(PrinterRecord printer, HttpRequest request, Outcome outcome) {
    Map<String, String> query = request.query();
    GetJobInput input =
        Json.bind(
            GetJobInput.builder(),
            b -> b.mac(query.get("mac")).type(query.get("type")).token(query.get("token")));
    requireSame(printer, input.getMac());
    GetJobOutput output = service.getJob(printer.mac(), input.getType(), input.getToken(), outcome);
    byte[] body = new byte[output.getContent().remaining()];
    output.getContent().duplicate().get(body);
    return new HttpResponse(
        200, Map.of("Content-Type", output.getContentType(), "Cache-Control", "no-store"), body);
  }

  private HttpResponse confirm(PrinterRecord printer, HttpRequest request, Outcome outcome) {
    Map<String, String> query = request.query();
    ConfirmJobInput input =
        Json.bind(
            ConfirmJobInput.builder(),
            b -> b.mac(query.get("mac")).code(query.get("code")).token(query.get("token")));
    requireSame(printer, input.getMac());
    service.confirm(printer.mac(), input.getCode(), input.getToken(), outcome);
    return Json.ok(200, ConfirmJobOutput.builder().build());
  }

  /** The registered printer whose credentials the request carries. */
  private PrinterRecord authenticate(HttpRequest request) {
    Optional<BasicAuth> credentials = request.header("Authorization").flatMap(BasicAuth::parse);
    return credentials
        .flatMap(c -> Macs.parse(c.user()).flatMap(printers::get).map(p -> Map.entry(c, p)))
        .filter(e -> Passwords.matches(e.getKey().password(), e.getValue().passwordHash()))
        .map(Map.Entry::getValue)
        .orElseThrow(() -> unauthorized("Missing or wrong credentials."));
  }

  private static void requireSame(PrinterRecord printer, String mac) {
    if (!printer.mac().equals(Macs.normalize(mac))) {
      throw unauthorized("The credentials are for another printer.");
    }
  }

  private static Unauthorized unauthorized(String message) {
    return Unauthorized.builder().message(message).build();
  }
}
