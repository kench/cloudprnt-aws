package tv.kenley.cloudprnt.printer;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import software.amazon.smithy.java.core.serde.document.Document;
import software.amazon.smithy.model.shapes.ShapeType;
import tv.kenley.cloudprnt.common.Contents;
import tv.kenley.cloudprnt.common.JobRecord;
import tv.kenley.cloudprnt.common.JobState;
import tv.kenley.cloudprnt.common.Jobs;
import tv.kenley.cloudprnt.common.PollUpdate;
import tv.kenley.cloudprnt.common.PrinterRecord;
import tv.kenley.cloudprnt.common.Printers;
import tv.kenley.cloudprnt.protocol.model.ClientActionRequest;
import tv.kenley.cloudprnt.protocol.model.ClientActionResult;
import tv.kenley.cloudprnt.protocol.model.GetJobOutput;
import tv.kenley.cloudprnt.protocol.model.NoJob;
import tv.kenley.cloudprnt.protocol.model.PollInput;
import tv.kenley.cloudprnt.protocol.model.PollOutput;
import tv.kenley.cloudprnt.protocol.model.UnsupportedMediaType;

/**
 * The CloudPRNT protocol: polls record the printer's status and offer its oldest pending job;
 * fetches hand the job over; confirmations finish it. A job is fetched at most {@link
 * #MAX_ATTEMPTS} times.
 */
final class CloudPrntService {
  /** How many times a job may be fetched before it fails. */
  static final int MAX_ATTEMPTS = 5;

  /** How often to ask a printer that hasn't answered for its type, version and page size. */
  static final Duration INFO_RETRY = Duration.ofHours(1);

  private static final Set<JobState> PENDING = Set.of(JobState.QUEUED, JobState.SENT);

  private static final List<ClientActionRequest> INFO_REQUESTS =
      List.of(
          ClientActionRequest.builder().request("ClientType").options("").build(),
          ClientActionRequest.builder().request("ClientVersion").options("").build(),
          ClientActionRequest.builder().request("PageInfo").options("").build());

  private final Printers printers;
  private final Jobs jobs;
  private final Contents contents;
  private final Clock clock;

  CloudPrntService(Printers printers, Jobs jobs, Contents contents, Clock clock) {
    this.printers = printers;
    this.jobs = jobs;
    this.contents = contents;
    this.clock = clock;
  }

  /** Records a poll from {@code printer} and says whether a job is ready. */
  PollOutput poll(PrinterRecord printer, PollInput input, Outcome outcome) {
    Instant now = clock.instant();
    ClientInfo info = ClientInfo.from(input.getClientAction());
    boolean askForInfo =
        printer.clientType() == null
            && info.clientType() == null
            && !input.hasClientAction()
            && (printer.infoRequestedAt() == null
                || printer.infoRequestedAt().plus(INFO_RETRY).isBefore(now));
    printers.recordPoll(
        printer.mac(),
        new PollUpdate(
            now,
            input.getStatus(),
            decode(input.getStatusCode()),
            input.isPrintingInProgress(),
            info.clientType(),
            info.clientVersion(),
            info.printWidthDots(),
            askForInfo ? now : null));
    if (askForInfo) {
      outcome.result("clientInfoRequested");
      return PollOutput.builder().jobReady(false).clientAction(INFO_REQUESTS).build();
    }
    Optional<JobRecord> job = next(printer.mac(), now);
    if (job.isEmpty()) {
      outcome.result("noJob");
      return PollOutput.builder().jobReady(false).build();
    }
    outcome.result("jobReady").jobId(job.get().jobId());
    return PollOutput.builder()
        .jobReady(true)
        .mediaTypes(List.of(job.get().mediaType()))
        .jobToken(job.get().jobId())
        .build();
  }

  /** Hands over the printer's job in {@code mediaType}. */
  GetJobOutput getJob(String mac, String mediaType, String token, Outcome outcome) {
    Instant now = clock.instant();
    JobRecord job = find(mac, token).orElseThrow(CloudPrntService::noJob);
    outcome.jobId(job.jobId());
    if (!job.mediaType().equals(mediaType)) {
      throw UnsupportedMediaType.builder()
          .message("The job is available as " + job.mediaType() + ".")
          .build();
    }
    Optional<byte[]> content = contents.get(job.contentKey());
    if (content.isEmpty()) {
      jobs.transition(mac, job.jobId(), PENDING, JobState.FAILED, "Content expired", false, now);
      outcome.result("contentExpired");
      throw noJob();
    }
    if (jobs.transition(mac, job.jobId(), PENDING, JobState.SENT, null, true, now).isEmpty()) {
      throw noJob();
    }
    outcome.result("sent");
    return GetJobOutput.builder()
        .contentType(job.mediaType())
        .content(ByteBuffer.wrap(content.get()))
        .build();
  }

  /**
   * Finishes the printer's job: printed for a 2xx code, queued again for a download timeout (520)
   * while attempts remain, failed otherwise. Confirming a job that isn't pending does nothing.
   */
  void confirm(String mac, String code, String token, Outcome outcome) {
    Optional<JobRecord> found = find(mac, token);
    if (found.isEmpty()) {
      outcome.result("noJob");
      return;
    }
    JobRecord job = found.get();
    JobState to;
    if (code.startsWith("2")) {
      to = JobState.PRINTED;
    } else if (code.startsWith("520") && job.attempts() < MAX_ATTEMPTS) {
      to = JobState.QUEUED;
    } else {
      to = JobState.FAILED;
    }
    jobs.transition(mac, job.jobId(), PENDING, to, code, false, clock.instant());
    outcome.result(to.name().toLowerCase(java.util.Locale.ROOT)).jobId(job.jobId());
  }

  /** The job named by {@code token}, if pending, or else the printer's oldest pending job. */
  private Optional<JobRecord> find(String mac, String token) {
    if (token != null) {
      return jobs.get(mac, token).filter(job -> job.state().pending());
    }
    return jobs.next(mac);
  }

  /** The oldest pending job, failing any that have been fetched too often. */
  private Optional<JobRecord> next(String mac, Instant now) {
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      Optional<JobRecord> job = jobs.next(mac);
      if (job.isEmpty() || job.get().attempts() < MAX_ATTEMPTS) {
        return job;
      }
      jobs.transition(
          mac, job.get().jobId(), PENDING, JobState.FAILED, "Too many attempts", false, now);
    }
    return Optional.empty();
  }

  private static NoJob noJob() {
    return NoJob.builder().message("No job is waiting for this printer.").build();
  }

  /** A status code as the printer sends it, URL-encoded (such as {@code 200%20OK}), decoded. */
  static String decode(String statusCode) {
    if (statusCode == null) {
      return null;
    }
    try {
      return URLDecoder.decode(statusCode, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return statusCode;
    }
  }

  /**
   * What the printer reported in answer to client actions.
   *
   * @param clientType from {@code ClientType}
   * @param clientVersion from {@code ClientVersion}
   * @param printWidthDots from {@code PageInfo}: the print width (mm) times dots per mm
   */
  record ClientInfo(String clientType, String clientVersion, Integer printWidthDots) {
    static ClientInfo from(List<ClientActionResult> results) {
      String type = null;
      String version = null;
      Integer width = null;
      for (ClientActionResult result : results == null ? List.<ClientActionResult>of() : results) {
        switch (result.getRequest()) {
          case "ClientType" -> type = text(result.getResult());
          case "ClientVersion" -> version = text(result.getResult());
          case "PageInfo" -> width = printWidthDots(result.getResult());
          default -> {}
        }
      }
      return new ClientInfo(type, version, width);
    }

    private static String text(Document value) {
      if (value == null) {
        return null;
      }
      if (value.isType(ShapeType.STRING)) {
        return value.asString();
      }
      return switch (value.type()) {
        case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL ->
            value.asNumber().toString();
        default -> null;
      };
    }

    private static Integer printWidthDots(Document value) {
      if (value == null || !(value.isType(ShapeType.MAP) || value.isType(ShapeType.STRUCTURE))) {
        return null;
      }
      String width = text(value.getMember("printWidth"));
      String resolution = text(value.getMember("horizontalResolution"));
      if (width == null || resolution == null) {
        return null;
      }
      try {
        return (int) Math.round(Double.parseDouble(width) * Double.parseDouble(resolution));
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }
}
