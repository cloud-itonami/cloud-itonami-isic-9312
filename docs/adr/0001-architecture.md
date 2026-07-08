# ADR-0001: ClubOps-LLM ⊣ Membership Governor architecture

## Status

Accepted. `cloud-itonami-isic-9312` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-9312` publishes an OSS business blueprint for
activities of sports clubs: membership-based athletic and sporting
clubs. Like every prior actor in this fleet, the blueprint alone is
not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the
same langgraph StateGraph + independent Governor + Phase 0→3 rollout
pattern established by `cloud-itonami-isic-6511` (life insurance) and
applied across seventy-two prior siblings, most recently
`cloud-itonami-isic-9329` (other amusement/recreation activities).

## Decision

### Decision 1: single-actuation shape, and why the check family is mostly honest reuse rather than new claims

This blueprint's own README/business-model.md/operator-guide.md
consistently name only ONE real-world act: "finalizing a membership
suspension or expulsion" -- both terms treated as ONE conceptual act,
`high-stakes` a one-member set `#{:actuation/finalize-membership-
action}`. Before designing the check family, every remaining
`:blueprint`-tier candidate's `:itonami.blueprint/governor` keyword
was checked against every already-implemented sibling's own governor
name: `cloud-itonami-isic-7220`/`8522`/`8549`/`9411`/`9512`/`9522`/
`9523`/`9524`/`9529` were all found to declare the IDENTICAL governor
keyword as an already-implemented sibling (`research-integrity-
governor` colliding with `research`/7210; `curriculum-safeguarding-
governor` colliding with `secondary`/8521; `instruction-integrity-
governor` colliding with `cultural`/8542; `association-governance-
governor` colliding with `association`/9412; `repair-shop-governor`
colliding with `repairshop`/9521, five times over). ISIC 9312's own
`:membership-governor` was confirmed to have NO such collision
(distinct from `association`/9412's `association-governance-
governor`, `congregation`/9491's `congregational-governance-
governor`, and `union`/9420's `union-governance-governor`), making it
the strongest remaining candidate. Given this, and given the domain's
own check-family space is now heavily covered by 72 prior builds, this
build's own check family is HONESTLY characterized as reuse rather
than forcing strained novelty claims -- see Decisions 3-4.

### Decision 2: entity and op shape

The primary entity is a `member`, matching the business-model.md's
own Offer language ("member enrollment intake"). Four ops:
`:member/intake` (directory upsert, no capital risk), `:eligibility/
verify` (per-jurisdiction membership-governance evidence checklist,
never auto), `:conduct/screen` (disciplinary-complaint screening,
unconditional-evaluation discipline, never auto), and `:actuation/
finalize-membership-action` (POSITIVE, high-stakes -- finalizing a
real membership suspension or expulsion).

### Decision 3: `disciplinary-complaint-unresolved-violations` -- an honest reuse of `association`/9412's own concept, the 58th unconditional-evaluation grounding

`association.governor/complaint-unresolved-violations` (ISIC 9412,
professional/trade associations) already established this exact
concept for a membership-organization context. `sportsclub.governor/
disciplinary-complaint-unresolved-violations` reuses it directly for
sports clubs -- the 58th distinct application of the unconditional-
evaluation discipline overall (`casualty.governor/sanctions-
violations`'s original fix; most recently `recreation.governor/
emergency-egress-obstructed-violations` at 57th). NOT claimed as new.
Gates `:conduct/screen` and the actuation.

### Decision 4: `appeal-window-still-open?` -- an honest reuse of `personalservice`/9609's MINIMUM-threshold sufficiency SHAPE, for a genuinely different real-world concept

`personalservice.registry/cooling-off-period-not-elapsed?` (the
eighth instance of this fleet's MINIMUM-threshold sufficiency family)
established the "elapsed time must satisfy a minimum before an act
may proceed" shape, grounded in consumer cooling-off/rescission
rights. `sportsclub.registry/appeal-window-still-open?` reuses that
exact SHAPE (comparing a member's own `:days-since-suspension-notice`
against its own `:minimum-appeal-window-days`) for the TENTH instance
of the family overall, but grounds it in a genuinely different
real-world concept: nonprofit membership-organization due-process
requirements before a member suspension/expulsion may be finalized
(Germany's BGB §35 Vereinsrecht right to be heard; California
Corporations Code §7341 nonprofit member-expulsion notice-and-hearing
requirements; Japan's 一般社団法人及び一般財団法人に関する法律 第25条
member-expulsion due process). Not claimed as a new shape -- the
shape is `personalservice`'s; only the domain application is new.
Gates only the actuation (a pure ground-truth recompute).

### Decision 5: dedicated double-actuation-guard boolean

`:membership-action-finalized?` is a dedicated boolean on the
`member` record, never a single `:status` value -- the same
discipline every prior sibling governor's guards establish, informed
by `cloud-itonami-isic-6492`'s status-lifecycle bug
(ADR-2607071320).

### Decision 6: Store protocol, MemStore + DatomicStore parity

`sportsclub.store/Store` is implemented by both `MemStore` (atom-
backed, default for dev/tests/demo) and `DatomicStore` (`langchain.
db`-backed), proven to satisfy the same contract in `test/
sportsclub/store_contract_test.clj` -- the same seam every sibling
actor uses so swapping the SSoT backend is a configuration change,
not a rewrite. The protocol's per-entity accessor is named `member`
directly -- not a Clojure special form, so no `-of` suffix workaround
was needed.

### Decision 7: Phase 0→3 rollout

Phase 3's `:auto` set has exactly one member, `:member/intake` (no
capital risk). `:eligibility/verify` and `:conduct/screen` are never
auto-eligible at any phase (matching every sibling's screening/
verification-op posture), and `:actuation/finalize-membership-action`
is permanently excluded from every phase's `:auto` set -- a
structural fact, not a rollout milestone, enforced by BOTH
`sportsclub.phase` and `sportsclub.governor`'s `high-stakes` set
independently.

### Decision 8: no bespoke domain capability lib

This blueprint's own `:itonami.blueprint/required-technologies`
names no domain-specific capability beyond the generic robotics/
identity/forms/dmn/bpmn/audit-ledger stack -- there was no
capability-lib decision to make at all.

### Decision 9: mock + LLM advisor pair

`sportsclub.clubopsllm` provides `mock-advisor` (deterministic,
default everywhere -- the actor graph and governor contract run
offline) and `llm-advisor` (backed by `langchain.model/ChatModel`,
with a defensive EDN-proposal parser so a malformed LLM response
degrades to a safe low-confidence noop rather than ever auto-
finalizing a membership action).

### Decision 10: no `blueprint.edn` field-sync fixes needed

Matching `photo`/7420's, `personalservice`/9609's, `edsupport`/8550's,
`headoffice`/7010's, `residential`/8790's, `cultural`/8542's,
`reserve`/6411's, `proserv`/7490's, `sportsevent`/9319's and
`recreation`/9329's own experience, this repo's `blueprint.edn`
already had the correct `isic-` prefixed `:id` and correctly
populated `:required-technologies`/`:optional-technologies` matching
the `kotoba-lang/industry` registry's own entry for `"9312"` exactly
-- only the `:maturity` field itself needed adding.

## Alternatives considered

- **Building `cloud-itonami-isic-9411` (business/employers membership
  organizations) instead.** Rejected: its own `:itonami.blueprint/
  governor` (`:association-governance-governor`) is an EXACT
  collision with `association`/9412's already-implemented governor
  name -- confirmed via direct blueprint.edn comparison. It is also a
  live test fixture in `industry_test.clj`, requiring a fixture
  repoint if ever selected.
- **Building one of the repair-category candidates (9512/9522/9523/
  9524/9529) instead.** Rejected for the same reason: all five
  declare the IDENTICAL `:repair-shop-governor` keyword already used
  by `repairshop`/9521.
- **Forcing a "genuinely new" check to avoid an all-reuse build.**
  Rejected: after 72 prior builds, the check-family space for a
  membership-suspension/expulsion actuation shape is well covered by
  `association`/9412's and `personalservice`/9609's own established
  concepts; honestly characterizing both of this build's checks as
  reuses (of a concept, and of a shape for a new domain,
  respectively) is more defensible than straining for an unearned
  novelty claim.

## Consequences

- Seventy-fourth actor promoted in this fleet's registry (73
  implemented before this build).
- Documents an honest reuse of `association`/9412's own disciplinary-
  complaint-unresolved concept (58th unconditional-eval grounding),
  not claimed as new.
- Documents an honest reuse of `personalservice`/9609's MINIMUM-
  threshold sufficiency SHAPE (10th instance), applied to a genuinely
  different real-world concept (nonprofit due-process appeal window,
  not consumer cooling-off), not claimed as a new shape.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/sportsclub/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- `blueprint.edn` required no field-sync fixes this time (already
  correct) -- only the `:maturity` flip itself.
- Confirms, via direct governor-name comparison across all 12
  remaining `:blueprint`-tier candidates at build time, that 9 of the
  other 11 are blocked by an exact governor-name collision with an
  already-implemented sibling -- a fleet-wide observation worth
  keeping in mind for future vertical selection.

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9312/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9312/docs/business-model.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9412/src/association/governor.cljc` (`complaint-unresolved-violations` origin)
- `orgs/cloud-itonami/cloud-itonami-isic-9609/src/personalservice/registry.cljc` (`cooling-off-period-not-elapsed?` origin)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9312"`)
