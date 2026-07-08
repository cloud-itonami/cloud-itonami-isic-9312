# Business Model: Activities of sports clubs

## Classification

- Repository: `cloud-itonami-isic-9312`
- ISIC Rev.5: `9312`
- Activity: activities of sports clubs -- membership-based athletic and sporting clubs
- Social impact: cultural/recreational access, data sovereignty, transparent audit

## Customer

- independent sports clubs
- cooperative athletic associations
- community sporting-league operators

## Offer

- member enrollment intake
- schedule/competition proposal
- membership-suspension/expulsion proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per club
- support: monthly retainer with SLA
- migration: import from an incumbent club-management system
- per-member fee

## Trust Controls

- no membership suspension or expulsion is finalized without human sign-off
- a fabricated conduct/eligibility record forces a hold, not an override
- every membership-action path is auditable
- member personal data stays outside Git
- emergency manual override paths remain outside LLM control
- an unresolved disciplinary complaint, or a member's own due-process
  appeal window that has not yet elapsed, forces a hold, not an
  override
- membership-action finalization is logged and escalated, and cannot
  be finalized twice for the same member: a double-finalization
  attempt is held off this actor's own member facts alone, with no
  upstream comparison needed

## Membership Governor: decision rule

`blueprint.edn` fixes `:itonami.blueprint/governor` to `:membership-
governor` -- this is not a generic "review step," it is the one gate
the ONE real-world act this business performs (finalizing a real
membership suspension or expulsion) must pass. The governor sits
between the ClubOps-LLM and execution, per the README's Core
Contract:

```text
ClubOps-LLM -> Membership Governor -> hold, proceed, or human approval
```

**Approves**: routine sports-club member actions proposed against a
member that already has a consented eligibility evidence checklist on
file, satisfied required evidence, a resolved disciplinary-complaint
status, and an elapsed due-process appeal window. These proceed
straight to the member ledger.

**Rejects or escalates**: the governor refuses to let the advisor
finalize a membership action on its own authority when any of the
following hold -- a fabricated jurisdiction spec-basis; incomplete
evidence; an unresolved disciplinary complaint; a still-open appeal
window; a double-finalization attempt. A clean finalization proposal
still always routes to a human -- `:actuation/finalize-membership-
action` is never auto-committed, at any rollout phase.
