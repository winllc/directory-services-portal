package com.winllc.dsp.jobs;

import com.winllc.dsp.util.LogSafe;
import com.winllc.dsp.analysis.AnalysisResult;
import com.winllc.dsp.cluster.Cluster;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared copy of background jobs ({@code dsp_job}), so a user's job list, progress and
 * results show whichever replica a request reaches. The replica running a job writes its view when
 * it changes (and its result once it succeeded); others read it from here and ask for a cancel by
 * setting a flag that the running replica acts on.
 */
final class JobMirror {
  private static final Logger log = LoggerFactory.getLogger(JobMirror.class);

  private final JdbcTemplate jdbc;
  private final JsonMapper mapper;
  private final String replica;
  /** The revision of each local job last written. */
  private final Map<String, Long> published = new ConcurrentHashMap<>();

  JobMirror(Cluster cluster, JsonMapper mapper) {
    this.jdbc = cluster.jdbc();
    this.mapper = mapper;
    this.replica = cluster.replicaId();
  }

  /** Writes a local job's view (and result, once it succeeded) when it changed since last time. */
  synchronized void publish(Job job) {
    Job.Snapshot snap = job.snapshot();
    Long last = published.get(job.id);
    if (last != null && last == snap.revision()) return;
    String result = null;
    if (snap.status() == Job.Status.SUCCEEDED && !job.resultPublished) {
      Object value = snap.result();
      try {
        result = mapper.writeValueAsString(Map.of("type", value instanceof AnalysisResult ? "analysis" : "json", "value", value == null ? "" : value));
        job.resultPublished = true;
      } catch (RuntimeException e) {
        log.warn("Could not share the result of job {}: {}", LogSafe.of(job.id), LogSafe.of(e.getMessage()));
      }
    }
    try {
      jdbc.update("""
          INSERT INTO dsp_job (id, owner, replica, view_json, result_json, status, background, created_at, finished_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (id) DO UPDATE SET view_json = EXCLUDED.view_json, status = EXCLUDED.status, background = EXCLUDED.background,
            result_json = coalesce(EXCLUDED.result_json, dsp_job.result_json), finished_at = EXCLUDED.finished_at, updated_at = now()""",
          job.id, job.owner, replica, mapper.writeValueAsString(snap.view()), result, snap.status().name(), snap.background(),
          Timestamp.from(job.createdAt), snap.finishedAt() == null ? null : Timestamp.from(snap.finishedAt()));
      published.put(job.id, snap.revision());
    } catch (RuntimeException e) {
      log.warn("Could not share job {}: {}", LogSafe.of(job.id), LogSafe.of(e.getMessage()));
    }
  }

  void forget(String id) {
    published.remove(id);
  }

  private record Row(String owner, String view, String result, boolean background, boolean present) {}

  private final RowMapper<Row> rows = (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), true);

  /** A job from the shared table, or null. {@code withResult}: also read its result. */
  Job find(String id, boolean withResult) {
    List<Row> found = jdbc.query("SELECT owner, view_json, " + (withResult ? "result_json" : "NULL") + ", background FROM dsp_job WHERE id = ?", rows, id);
    return found.isEmpty() ? null : toJob(found.get(0));
  }

  /** The user's background jobs, newest first. */
  List<Job> backgroundOf(String owner) {
    return jdbc.query("SELECT owner, view_json, NULL, background FROM dsp_job WHERE owner = ? AND background ORDER BY created_at DESC", rows, owner)
        .stream().map(this::toJob).toList();
  }

  long activeOf(String owner) {
    Long n = jdbc.queryForObject("SELECT count(*) FROM dsp_job WHERE owner = ? AND status IN ('QUEUED', 'RUNNING')", Long.class, owner);
    return n == null ? 0 : n;
  }

  boolean exists(String id) {
    return !jdbc.queryForList("SELECT 1 FROM dsp_job WHERE id = ?", id).isEmpty();
  }

  void requestCancel(String id) {
    jdbc.update("UPDATE dsp_job SET cancel_requested = true, updated_at = now() WHERE id = ?", id);
  }

  void markSeen(String id) {
    Job j = find(id, false);
    if (j == null) return;
    j.markSeen();
    jdbc.update("UPDATE dsp_job SET view_json = ?, updated_at = now() WHERE id = ?", mapper.writeValueAsString(j.view()), id);
  }

  void delete(String id) {
    jdbc.update("DELETE FROM dsp_job WHERE id = ?", id);
    published.remove(id);
  }

  /** Ids of this replica's jobs that another replica asked to cancel. */
  List<String> cancelRequests() {
    return jdbc.queryForList("SELECT id FROM dsp_job WHERE replica = ? AND cancel_requested AND status IN ('QUEUED', 'RUNNING')", String.class, replica);
  }

  /** Drops finished jobs past their retention (any replica may run this). */
  void expire(Duration retain) {
    jdbc.update("DELETE FROM dsp_job WHERE finished_at IS NOT NULL AND finished_at < ?", Timestamp.from(Instant.now().minus(retain)));
  }

  private Job toJob(Row r) {
    Job.View view = mapper.readValue(r.view(), Job.View.class);
    Object result = null;
    if (r.result() != null) {
      JsonNode node = mapper.readTree(r.result());
      JsonNode value = node.get("value");
      result = "analysis".equals(node.path("type").asString()) ? mapper.treeToValue(value, AnalysisResult.class) : value;
    }
    return Job.remote(view, r.owner(), result, r.background());
  }
}
