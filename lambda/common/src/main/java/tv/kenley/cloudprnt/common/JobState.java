package tv.kenley.cloudprnt.common;

/** Where a job is. {@code QUEUED} and {@code SENT} jobs are pending; the rest are finished. */
public enum JobState {
  QUEUED,
  SENT,
  PRINTED,
  FAILED,
  CANCELLED;

  /** Whether the printer may still fetch the job. */
  public boolean pending() {
    return this == QUEUED || this == SENT;
  }
}
