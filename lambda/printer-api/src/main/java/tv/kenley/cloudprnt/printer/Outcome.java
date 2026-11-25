package tv.kenley.cloudprnt.printer;

/** What a request did, for its log line. */
final class Outcome {
  private String result;
  private String jobId;

  Outcome result(String result) {
    this.result = result;
    return this;
  }

  String result() {
    return result;
  }

  Outcome jobId(String jobId) {
    this.jobId = jobId;
    return this;
  }

  String jobId() {
    return jobId;
  }
}
