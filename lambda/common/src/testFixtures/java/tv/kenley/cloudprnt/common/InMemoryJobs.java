package tv.kenley.cloudprnt.common;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** {@link Jobs} in memory, for tests. */
public final class InMemoryJobs implements Jobs {
  private final Map<String, JobRecord> jobs = new TreeMap<>();

  private static String key(String mac, String jobId) {
    return mac + "/" + jobId;
  }

  @Override
  public void create(JobRecord job) {
    if (jobs.putIfAbsent(key(job.mac(), job.jobId()), job) != null) {
      throw new IllegalStateException("duplicate job " + job.jobId());
    }
  }

  @Override
  public Optional<JobRecord> get(String mac, String jobId) {
    return Optional.ofNullable(jobs.get(key(mac, jobId)));
  }

  @Override
  public Optional<JobRecord> next(String mac) {
    return jobs.values().stream()
        .filter(j -> j.mac().equals(mac) && j.state().pending())
        .min(Comparator.comparing(JobRecord::jobId));
  }

  @Override
  public Page<JobRecord> list(String mac, int limit, Optional<String> after) {
    List<JobRecord> rest =
        jobs.values().stream()
            .filter(j -> j.mac().equals(mac))
            .sorted(Comparator.comparing(JobRecord::jobId).reversed())
            .filter(j -> after.isEmpty() || j.jobId().compareTo(after.get()) < 0)
            .toList();
    List<JobRecord> page = rest.stream().limit(limit).toList();
    return new Page<>(
        page, rest.size() > limit ? Optional.of(page.getLast().jobId()) : Optional.empty());
  }

  @Override
  public Optional<JobRecord> transition(
      String mac,
      String jobId,
      Set<JobState> from,
      JobState to,
      String result,
      boolean fetched,
      Instant now) {
    JobRecord j = jobs.get(key(mac, jobId));
    if (j == null || !from.contains(j.state())) {
      return Optional.empty();
    }
    JobRecord updated =
        new JobRecord(
            j.mac(),
            j.jobId(),
            j.mediaType(),
            j.size(),
            to,
            j.createdAt(),
            now,
            j.attempts() + (fetched ? 1 : 0),
            result != null ? result : j.result());
    jobs.put(key(mac, jobId), updated);
    return Optional.of(updated);
  }
}
