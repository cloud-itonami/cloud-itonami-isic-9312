# Business Model: Activities of sports clubs

## Classification

- Repository: `cloud-itonami-9312`
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
