package tv.kenley.cloudprnt.jobs;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import software.amazon.smithy.java.framework.model.ValidationException;
import tv.kenley.cloudprnt.api.model.JobNotCancellable;
import tv.kenley.cloudprnt.api.model.JobNotFound;
import tv.kenley.cloudprnt.api.model.ListPrintJobsOutput;
import tv.kenley.cloudprnt.api.model.ListPrintersOutput;
import tv.kenley.cloudprnt.api.model.PrintJobSummary;
import tv.kenley.cloudprnt.api.model.PrinterExists;
import tv.kenley.cloudprnt.api.model.PrinterNotFound;
import tv.kenley.cloudprnt.api.model.PrinterSummary;
import tv.kenley.cloudprnt.api.model.RegisterPrinterOutput;
import tv.kenley.cloudprnt.common.Contents;
import tv.kenley.cloudprnt.common.JobRecord;
import tv.kenley.cloudprnt.common.JobState;
import tv.kenley.cloudprnt.common.Jobs;
import tv.kenley.cloudprnt.common.Macs;
import tv.kenley.cloudprnt.common.Page;
import tv.kenley.cloudprnt.common.Passwords;
import tv.kenley.cloudprnt.common.PrinterRecord;
import tv.kenley.cloudprnt.common.Printers;
import tv.kenley.cloudprnt.common.Ulid;

/** Registering printers and queueing their jobs. MAC addresses arrive validated. */
final class JobService {
  /** The page size when a caller doesn't give one. */
  static final int DEFAULT_PAGE_SIZE = 50;

  private final Printers printers;
  private final Jobs jobs;
  private final Contents contents;
  private final Clock clock;
  private final SecureRandom random;

  JobService(Printers printers, Jobs jobs, Contents contents, Clock clock, SecureRandom random) {
    this.printers = printers;
    this.jobs = jobs;
    this.contents = contents;
    this.clock = clock;
    this.random = random;
  }

  RegisterPrinterOutput register(String mac, String name) {
    String password = Passwords.generate(random);
    PrinterRecord printer =
        PrinterRecord.registered(
            Macs.normalize(mac), name, Passwords.hash(password), clock.instant());
    if (!printers.create(printer)) {
      throw PrinterExists.builder().message("This printer is already registered.").build();
    }
    return RegisterPrinterOutput.builder().printer(summary(printer)).password(password).build();
  }

  PrinterSummary getPrinter(String mac) {
    return summary(printer(mac));
  }

  ListPrintersOutput listPrinters(String nextToken, Integer maxResults) {
    Page<PrinterRecord> page = printers.list(pageSize(maxResults), decodeToken(nextToken));
    return ListPrintersOutput.builder()
        .printers(page.items().stream().map(JobService::summary).toList())
        .nextToken(page.last().map(JobService::encodeToken).orElse(null))
        .build();
  }

  void deletePrinter(String mac) {
    if (!printers.delete(Macs.normalize(mac))) {
      throw printerNotFound();
    }
  }

  String resetPassword(String mac) {
    String password = Passwords.generate(random);
    if (!printers.setPasswordHash(Macs.normalize(mac), Passwords.hash(password))) {
      throw printerNotFound();
    }
    return password;
  }

  PrintJobSummary submit(String mac, String mediaType, ByteBuffer content) {
    PrinterRecord printer = printer(mac);
    Instant now = clock.instant();
    byte[] bytes = new byte[content.remaining()];
    content.duplicate().get(bytes);
    JobRecord job =
        new JobRecord(
            printer.mac(),
            Ulid.generate("j-", now, random),
            mediaType,
            bytes.length,
            JobState.QUEUED,
            now,
            now,
            0,
            null);
    // The content first: a job the printer can see must have its content.
    contents.put(job.contentKey(), mediaType, bytes);
    jobs.create(job);
    return summary(job);
  }

  PrintJobSummary getJob(String mac, String jobId) {
    return summary(jobs.get(Macs.normalize(mac), jobId).orElseThrow(JobService::jobNotFound));
  }

  ListPrintJobsOutput listJobs(String mac, String nextToken, Integer maxResults) {
    Page<JobRecord> page =
        jobs.list(Macs.normalize(mac), pageSize(maxResults), decodeToken(nextToken));
    return ListPrintJobsOutput.builder()
        .jobs(page.items().stream().map(JobService::summary).toList())
        .nextToken(page.last().map(JobService::encodeToken).orElse(null))
        .build();
  }

  PrintJobSummary cancel(String mac, String jobId) {
    String normalized = Macs.normalize(mac);
    Optional<JobRecord> cancelled =
        jobs.transition(
            normalized,
            jobId,
            Set.of(JobState.QUEUED),
            JobState.CANCELLED,
            null,
            false,
            clock.instant());
    if (cancelled.isPresent()) {
      return summary(cancelled.get());
    }
    if (jobs.get(normalized, jobId).isEmpty()) {
      throw jobNotFound();
    }
    throw JobNotCancellable.builder()
        .message("The job has already been fetched or finished.")
        .build();
  }

  private PrinterRecord printer(String mac) {
    return printers.get(Macs.normalize(mac)).orElseThrow(JobService::printerNotFound);
  }

  private static int pageSize(Integer maxResults) {
    return maxResults == null ? DEFAULT_PAGE_SIZE : maxResults;
  }

  /** Pagination tokens are the last key, base64url-encoded. */
  static String encodeToken(String key) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(key.getBytes(StandardCharsets.UTF_8));
  }

  static Optional<String> decodeToken(String token) {
    if (token == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw ValidationException.builder().message("The nextToken isn't valid.").build();
    }
  }

  static PrinterSummary summary(PrinterRecord p) {
    return PrinterSummary.builder()
        .mac(p.mac())
        .name(p.name())
        .createdAt(p.createdAt())
        .lastPollAt(p.lastPollAt())
        .statusCode(p.statusCode())
        .status(p.status())
        .printingInProgress(p.printingInProgress())
        .clientType(p.clientType())
        .clientVersion(p.clientVersion())
        .printWidthDots(p.printWidthDots())
        .build();
  }

  static PrintJobSummary summary(JobRecord j) {
    return PrintJobSummary.builder()
        .jobId(j.jobId())
        .mac(j.mac())
        .mediaType(j.mediaType())
        .size(j.size())
        .state(tv.kenley.cloudprnt.api.model.JobState.from(j.state().name()))
        .createdAt(j.createdAt())
        .updatedAt(j.updatedAt())
        .attempts(j.attempts())
        .result(j.result())
        .build();
  }

  private static PrinterNotFound printerNotFound() {
    return PrinterNotFound.builder().message("No printer is registered with this address.").build();
  }

  private static JobNotFound jobNotFound() {
    return JobNotFound.builder().message("The printer has no job with this ID.").build();
  }
}
