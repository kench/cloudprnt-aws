package tv.kenley.cloudprnt.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** Print jobs, by printer. */
public interface Jobs {
  /** How long a finished job is kept. */
  Duration RETENTION = Duration.ofDays(30);

  /** Adds a new job. */
  void create(JobRecord job);

  /** A printer's job. */
  Optional<JobRecord> get(String mac, String jobId);

  /** The printer's oldest pending job. */
  Optional<JobRecord> next(String mac);

  /** Up to {@code limit} of a printer's jobs, newest first, after job {@code after}. */
  Page<JobRecord> list(String mac, int limit, Optional<String> after);

  /**
   * Moves a job to {@code to}, if it is in one of the {@code from} states.
   *
   * @param result the printer's result code to record, or {@code null} to keep the current one
   * @param fetched whether to count a fetch
   * @return the updated job, or empty if it doesn't exist or isn't in a {@code from} state
   */
  Optional<JobRecord> transition(
      String mac,
      String jobId,
      Set<JobState> from,
      JobState to,
      String result,
      boolean fetched,
      Instant now);
}
