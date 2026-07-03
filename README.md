# cloud-itonami-9312

Open Business Blueprint for **ISIC Rev.5 9312**: Activities of sports clubs.

This repository designs a forkable OSS business for activities of sports clubs -- membership-based athletic and sporting clubs -- run by a qualified, licensed operator so a community or
independent operator never surrenders patron/collection data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a facility-access control robot manages physical club-premises access,
under an actor that proposes actions and an independent **Membership Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + operational records
        |
        v
ClubOps-LLM -> Membership Governor -> hold, proceed, or human approval
        |
        v
operational ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: finalizing a membership suspension or expulsion.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`9312`). This vertical's operational records are practice-specific
rather than a shared cross-operator data contract, so it runs on the generic
identity/forms/dmn/bpmn/audit-ledger stack -- no bespoke domain capability lib.

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`ClubOps-LLM` + `Membership Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
