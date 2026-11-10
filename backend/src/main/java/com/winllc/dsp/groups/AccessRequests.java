package com.winllc.dsp.groups;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.SharedJsonList;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.web.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requests to join groups: in {@code group-access-requests.json} in the data directory, or the
 * shared database's {@value #DOCUMENT} document. Open requests are kept until decided or cancelled;
 * closed ones for {@link #KEEP_FOR}, at most {@link #KEEP_CLOSED} of them (the oldest go first).
 * The audit trail keeps every request and decision for good.
 */
@Component
public class AccessRequests {
  static final String DOCUMENT = "group-access-requests";
  static final String FILE = "group-access-requests.json";
  static final Duration KEEP_FOR = Duration.ofDays(365);
  static final int KEEP_CLOSED = 5_000;

  public static final String PENDING = "pending";
  public static final String APPROVED = "approved";
  public static final String REJECTED = "rejected";
  public static final String CANCELLED = "cancelled";

  /**
   * A request by {@code requester} (their {@code SessionUser.key()}; {@code requesterDn},
   * {@code requesterName} for display) to be added to {@code groupDn} of {@code definitionId} as
   * {@code member} (their DN, or their login name for memberUid groups).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Request(String id, String definitionId, String groupDn, String groupName, String requester, String requesterDn,
      String requesterName, String member, String reason, String status, String createdAt, String decidedAt, String decidedBy,
      String decidedByName, String note) {

    public boolean pending() {
      return PENDING.equals(status);
    }

    Request closed(String newStatus, String at, String by, String byName, String withNote) {
      return new Request(id, definitionId, groupDn, groupName, requester, requesterDn, requesterName, member, reason, newStatus, createdAt, at,
          by, byName, withNote);
    }
  }

  private final SharedJsonList<Request> requests;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public AccessRequests(PortalProperties props, JsonMapper mapper, Cluster cluster) {
    this(Path.of(props.getDataDir()).toAbsolutePath().resolve(FILE), mapper, cluster, Clock.systemUTC());
  }

  AccessRequests(Path file, JsonMapper mapper, Cluster cluster, Clock clock) {
    this.requests = new SharedJsonList<>(DOCUMENT, file, mapper, cluster, new TypeReference<List<Request>>() {});
    this.clock = clock;
  }

  private String now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS).toString();
  }

  /** Every kept request, newest first. */
  public List<Request> list() {
    return requests.list().reversed();
  }

  public List<Request> list(Predicate<Request> filter) {
    return list().stream().filter(filter).toList();
  }

  public Request find(String id) {
    return requests.list().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
  }

  /** Adds a pending request, unless the requester already has one for the group (409). */
  public Request add(String definitionId, String groupDn, String groupName, String requester, String requesterDn, String requesterName,
      String member, String reason, int maxPendingPerUser) {
    Request r = new Request("req-" + UUID.randomUUID(), definitionId, groupDn, groupName, requester, requesterDn, requesterName, member, reason,
        PENDING, now(), null, null, null, null);
    store(list -> {
      List<Request> mine = list.stream().filter(x -> x.pending() && x.requester().equals(requester)).toList();
      if (mine.stream().anyMatch(x -> x.definitionId().equals(definitionId) && com.winllc.dsp.ldap.Dns.equal(x.groupDn(), groupDn))) {
        throw new ApiException(HttpStatus.CONFLICT, "You already asked to join this group");
      }
      if (mine.size() >= maxPendingPerUser) {
        throw new ApiException(HttpStatus.CONFLICT, "You have " + mine.size() + " open requests; wait for some to be decided first");
      }
      list.add(r);
    });
    return r;
  }

  /** Closes a pending request (409 when it was decided or cancelled meanwhile); returns it closed. */
  public Request close(String id, String status, String by, String byName, String note) {
    String at = now();
    Request[] out = new Request[1];
    store(list -> {
      for (int i = 0; i < list.size(); i++) {
        Request r = list.get(i);
        if (!r.id().equals(id)) continue;
        if (!r.pending()) throw new ApiException(HttpStatus.CONFLICT, "This request was already " + r.status());
        out[0] = r.closed(status, at, by, byName, note);
        list.set(i, out[0]);
        return;
      }
      throw ApiException.notFound("Request not found");
    });
    return out[0];
  }

  /**
   * Applies a change and prunes. An {@link ApiException} from the change (a conflict) leaves the
   * list as it was and is thrown afterwards: thrown inside, the shared store would report it as a
   * storage failure.
   */
  private void store(java.util.function.Consumer<List<Request>> change) {
    ApiException[] refused = new ApiException[1];
    try {
      requests.change(list -> {
        List<Request> before = List.copyOf(list);
        try {
          change.accept(list);
        } catch (ApiException e) {
          refused[0] = e;
          list.clear();
          list.addAll(before);
          return;
        }
        prune(list);
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (refused[0] != null) throw refused[0];
  }

  /** Removes every request (tests reset the portal's state with it). */
  public void clear() {
    try {
      requests.change(List::clear);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Drops closed requests past {@link #KEEP_FOR}, then the oldest closed ones beyond {@link #KEEP_CLOSED}. */
  private void prune(List<Request> list) {
    Instant cutoff = clock.instant().minus(KEEP_FOR);
    list.removeIf(r -> !r.pending() && r.decidedAt() != null && Instant.parse(r.decidedAt()).isBefore(cutoff));
    List<Request> closed = list.stream().filter(r -> !r.pending()).sorted(Comparator.comparing(Request::decidedAt)).toList();
    for (int i = 0; i < closed.size() - KEEP_CLOSED; i++) list.remove(closed.get(i));
  }
}
