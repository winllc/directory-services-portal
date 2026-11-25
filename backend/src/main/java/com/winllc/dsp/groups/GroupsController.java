package com.winllc.dsp.groups;

import com.winllc.dsp.audit.AccessDenials;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.ReadOnlyAllowed;
import com.winllc.dsp.license.RequiresFeature;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.Memberships;
import com.winllc.dsp.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service group access for every signed-in user: look groups up, see one's own, ask to join
 * or leave; and, for a group's owners, its members and the requests to decide (see
 * {@link GroupService}). Groups are named by their definition and DN ({@code ?dn=}).
 */
@RestController
@RequestMapping("/api/groups")
@RequiresFeature({LicensedFeature.DIRECTORY_EDITOR, LicensedFeature.SELF_SERVICE_PROFILE})
public class GroupsController {
  private final GroupService groups;
  private final AccessDenials denials;

  public GroupsController(GroupService groups, AccessDenials denials) {
    this.groups = groups;
    this.denials = denials;
  }

  /** {@code until}: the last day of the access asked for ({@code yyyy-MM-dd}, UTC); none: no end asked. */
  public record RequestInput(@Size(max = GroupService.MAX_REASON) String reason, @Size(max = 10) String until) {}

  /** {@code until}: an approval's end date, no later than the day asked for; none: as asked. */
  public record DecisionInput(@Size(max = GroupService.MAX_REASON) String note, @Size(max = 10) String until) {}

  /** {@code until}: the last day of the memberships added; none: the directory's longest, or no end. */
  public record MembersInput(@NotNull @Size(max = 1000) List<@Size(max = 1024) String> add, @NotNull @Size(max = 1000) List<@Size(max = 1024) String> remove,
      @Size(max = 10) String until) {}

  /** Several requests decided with one note: {@code approve} or reject. */
  public record DecisionsInput(@NotNull @Size(min = 1, max = GroupService.MAX_DECISIONS) List<@NotBlank @Size(max = 64) String> ids,
      @NotNull Boolean approve, @Size(max = GroupService.MAX_REASON) String note) {}

  public record Decisions(List<GroupService.DecisionOutcome> results, int decided, int failed) {}

  public record MemberUntilInput(@NotBlank @Size(max = 1024) String member, @Size(max = 10) String until) {}

  /** A pasted list of people: login names, e-mail addresses or DNs, one a line. */
  public record PeopleInput(@NotNull @Size(max = GroupService.MAX_PASTED) List<@Size(max = 1024) String> lines) {}

  public record OwnersInput(@NotNull @Size(max = GroupService.MAX_OWNER_CHANGES) List<@Size(max = 1024) String> add,
      @NotNull @Size(max = GroupService.MAX_OWNER_CHANGES) List<@Size(max = 1024) String> remove) {}

  private static String dn(String dn) {
    if (dn == null || dn.isBlank() || dn.length() > 1024) throw ApiException.badRequest("Name the group with ?dn=");
    return dn;
  }

  private static String query(String q) {
    if (q != null && q.length() > 256) throw ApiException.badRequest("Search text is too long");
    return q;
  }

  /** The directories whose groups the user can look up. */
  @GetMapping("/directories")
  public List<GroupService.GroupDirectory> directories(@AuthenticationPrincipal SessionUser user) {
    return groups.directoriesFor(user);
  }

  @GetMapping
  public GroupService.GroupList search(@AuthenticationPrincipal SessionUser user, @RequestParam(required = false) String q,
      @RequestParam(required = false) String definition) {
    return groups.search(user, query(q), definition);
  }

  @GetMapping("/mine")
  public GroupService.GroupList mine(@AuthenticationPrincipal SessionUser user) {
    return groups.mine(user);
  }

  @GetMapping("/{definitionId}/group")
  public GroupService.GroupDetail group(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId, @RequestParam String dn) {
    return groups.detail(user, definitionId, dn(dn));
  }

  @PostMapping("/{definitionId}/requests")
  public ResponseEntity<AccessRequests.Request> request(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId,
      @RequestParam String dn, @Valid @RequestBody RequestInput in) {
    return ResponseEntity.status(HttpStatus.CREATED).body(groups.request(user, definitionId, dn(dn), in.reason(), in.until()));
  }

  /** Joins an open group at once, without approval. */
  @PostMapping("/{definitionId}/join")
  public Memberships.Change join(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId, @RequestParam String dn) {
    return groups.join(user, definitionId, dn(dn));
  }

  @PostMapping("/{definitionId}/leave")
  public Memberships.Change leave(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId, @RequestParam String dn) {
    return groups.leave(user, definitionId, dn(dn));
  }

  // Owners -------------------------------------------------------------------

  @GetMapping("/{definitionId}/members")
  public GroupService.MemberPage members(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId, @RequestParam String dn,
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer page) {
    return groups.members(user, definitionId, dn(dn), query(q), page == null ? 1 : page);
  }

  @PostMapping("/{definitionId}/members")
  public Memberships.Change changeMembers(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId, @RequestParam String dn,
      @Valid @RequestBody MembersInput in) {
    return groups.changeMembers(user, definitionId, dn(dn), in.add(), in.remove(), in.until());
  }

  /** Sets, changes or (without {@code until}) clears a member's end date. */
  @PostMapping("/{definitionId}/members/until")
  public GroupService.Member memberUntil(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId, @RequestParam String dn,
      @Valid @RequestBody MemberUntilInput in) {
    return groups.setMemberUntil(user, definitionId, dn(dn), in.member(), in.until());
  }

  /** Adds people to and removes values from the group's owners (when its directory lets owners do so). */
  @PostMapping("/{definitionId}/owners")
  public Memberships.Change changeOwners(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId, @RequestParam String dn,
      @Valid @RequestBody OwnersInput in) {
    return groups.changeOwners(user, definitionId, dn(dn), in.add(), in.remove());
  }

  @GetMapping("/{definitionId}/people")
  public List<GroupService.Person> people(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId, @RequestParam String dn,
      @RequestParam(required = false) String q) {
    return groups.findPeople(user, definitionId, dn(dn), query(q));
  }

  /** Looks up a pasted list of people to add: who was found, who was not, and which lines are ambiguous. */
  @PostMapping("/{definitionId}/people/resolve")
  @ReadOnlyAllowed
  public GroupService.ResolvedPeople resolvePeople(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId,
      @RequestParam String dn, @Valid @RequestBody PeopleInput in) {
    return groups.resolvePeople(user, definitionId, dn(dn), in.lines());
  }

  @GetMapping("/{definitionId}/group/requests")
  public List<GroupService.RequestView> groupRequests(@AuthenticationPrincipal SessionUser user, @PathVariable String definitionId,
      @RequestParam String dn) {
    return groups.groupRequests(user, definitionId, dn(dn));
  }

  // Requests -----------------------------------------------------------------

  /** {@code scope}: mine (default), review or decided. */
  @GetMapping("/requests")
  public List<GroupService.RequestView> requests(@AuthenticationPrincipal SessionUser user, @RequestParam(required = false) String scope) {
    return groups.requests(user, scope);
  }

  /** Counts for the navigation: open requests to review, and the user's own. */
  @GetMapping("/requests/summary")
  public GroupService.Summary summary(@AuthenticationPrincipal SessionUser user) {
    return groups.summary(user);
  }

  @PostMapping("/requests/{id}/cancel")
  public AccessRequests.Request cancel(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    return groups.cancel(user, id);
  }

  @PostMapping("/requests/{id}/approve")
  public AccessRequests.Request approve(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @Valid @RequestBody DecisionInput in) {
    return groups.decide(user, id, true, in.note(), in.until());
  }

  /**
   * Approves or rejects several requests with one note, each on its own: the answer lists what was
   * decided and what was not, and why. Refusals are audited like those of single decisions.
   */
  @PostMapping("/requests/decide")
  public Decisions decideAll(@AuthenticationPrincipal SessionUser user, @Valid @RequestBody DecisionsInput in, HttpServletRequest request) {
    List<GroupService.DecisionOutcome> results = groups.decideAll(user, in.ids(), in.approve(), in.note());
    results.stream().filter(r -> r.code() != null && r.code() == HttpStatus.FORBIDDEN.value())
        .forEach(r -> denials.denied(request, "forbidden", r.error()));
    int decided = (int) results.stream().filter(GroupService.DecisionOutcome::ok).count();
    return new Decisions(results, decided, results.size() - decided);
  }

  @PostMapping("/requests/{id}/reject")
  public AccessRequests.Request reject(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @Valid @RequestBody DecisionInput in) {
    return groups.decide(user, id, false, in.note());
  }
}
