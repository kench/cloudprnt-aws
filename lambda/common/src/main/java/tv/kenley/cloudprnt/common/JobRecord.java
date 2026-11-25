package tv.kenley.cloudprnt.common;

import java.time.Instant;

/**
 * A print job.
 *
 * @param mac the printer's MAC address, in lower case
 * @param jobId the job ID
 * @param mediaType the content's media type
 * @param size the content's size in bytes
 * @param state where the job is
 * @param createdAt when it was submitted
 * @param updatedAt when its state last changed
 * @param attempts how many times the printer has fetched it
 * @param result the printer's last result code, or {@code null}
 */
public record JobRecord(
    String mac,
    String jobId,
    String mediaType,
    long size,
    JobState state,
    Instant createdAt,
    Instant updatedAt,
    int attempts,
    String result) {

  /** The S3 key of the job's content. */
  public String contentKey() {
    return contentKey(mac, jobId);
  }

  /** The S3 key of a job's content. */
  public static String contentKey(String mac, String jobId) {
    return "jobs/" + mac + "/" + jobId;
  }
}
