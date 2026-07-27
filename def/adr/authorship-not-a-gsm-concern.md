# ADR — Authorship is not a GSM/DM concern

**Status:** accepted

## Context

A proposal introduced attribution as three seeded rootless archetypes (`Attribution`,
`AttributionUser`, `AttributionSystem`) composed into seven GSM base statement schemas
via `allOf`, with `Archetype` excluded.

Review explored where authorship belongs: entity columns, a shared base ascription
schema, a wrapper indirection, the meta-schema, or the base archetypes. A two-site
statement-plane design was specified (`identity` + `identityIssuer`, defined once in
`Archetype.schema.json` `$defs`) before being reconsidered.

## Decision

**Authorship is not defined in GSM base schemas and requires no DM change.** It is
carried by client frameworks — specifically the ITIP framework layer.

## Rationale

### DM cannot be guarantor of the data it would declare

Authn/authz are held by an API gateway in the deployment target. DM verifies nothing, so
an `authorship` member in a GSM base schema is an assertion any authenticated client can
write. Declaring a field in the standard while being unable to vouch for a single value
is a claim of authority DM does not hold. The systems that can be guarantors — the
framework layer and the domain applications — are where the schema belongs.

### The design degraded precisely where ownership was missing

An author identity is personal data when it denotes a human and not when it denotes a
system, including technical identifiers. Distinguishing them requires knowing the
identity space, which DM does not.

The specification was therefore forced to strip both `$gsm:dataProtection` and
`$gsm:queryable` from `identity` and delegate them to tenants — leaving GSM declaring a
member while specifying nothing meaningful about it. A schema that must delegate a
field's entire governance treatment is declaring something it does not own. That
degradation was the signal, not an inconvenience to design around.

### It would write a deployment-specific identity architecture into a vendor-neutral standard

GSM is a vendor-neutral standard for defining and governing software-intensive systems.
The converged model is OIDC-shaped, IdP-dependent and GDPR-inflected — all deployment
concerns. Placing it in the eight base archetypes would impose one identity architecture
on every GSM adopter, including those with different or absent OIDC semantics.

Tenant extensibility exists for exactly this. Using it is the designed path, not a
workaround.

### Scope

For DM and GSM an author identity is information like any other, but too structuring to
be carried as a base concern. It is not part of what GSM governs; it is part of what a
client framework asserts about its own operations.

## Model carried to the framework layer

Two members, both required:

| member           | description                                                                                    |
| ---------------- | ---------------------------------------------------------------------------------------------- |
| `identity`       | Author identity — a human account or a system identity. Unique within `identityIssuer`.          |
| `identityIssuer` | Authority that issued `identity` (OIDC `iss`, client registry). The pair is what is unique.       |

`identityIssuer` is required because an identity is only unique within its issuing
authority (OIDC: `sub` is unique per `iss`). An optional issuer means unresolvable stored
authorship.

Rejected from the original proposal:

- **`authorship_type` discriminator** — DM performs no authn/authz and has no basis to
  distinguish human from system authors. With no variant-specific fields remaining,
  nothing to discriminate.
- **`authorship_issuer` as proposed** — variant-dependent meaning: namespace qualifier
  for the user case, identity itself for the system case.
- **`agent` / `principal` delegation chain** — DM performs no authn/authz and cannot
  interpret delegation. Behalf-of semantics belong to the gateway and the domain
  applications. Deferred to the framework layer, not adopted.
- **snake_case naming** — repository convention is camelCase.

## Trust model

Recorded here because it is the reason the concern moved, and because it states the
ceiling on what authorship means anywhere in the system.

**Human-authored ascriptions.** Framework applications derive `authorship` from the
authenticated session; users never declare it. Those applications MUST NOT accept
client-supplied authorship on human-authored paths.

**System-authored ascriptions.** The calling system declares its own authorship. This is
**trusted by admission**: the API gateway admits only registered clients, and
registration extends trust to that client's assertions. No layer verifies a declared
system identity against the caller — a registered system may legitimately author on its
own behalf, or post on behalf of another system, and the two cases are indistinguishable
from outside.

**Ceiling.** Authorship is suitable for provenance and audit context. It MUST NOT be
relied upon for non-repudiation or accountability. The assurance of system authorship is
exactly the rigour of client registration.

If authorship must become evidential, the gateway injects verified identity headers and
the ascribing component populates from them. The two-member model is unchanged by that
shift, which is why it is safe to adopt before that question is settled.

## Consequences

### No DM change

The framework layer covers both governance planes unaided:

- **instance statements** — a rootless `Authorship` archetype composed via `allOf` into
  framework bases; the standard tenant path, with `$gsm:queryable` and
  `$gsm:dataProtection` applied by whoever knows the identity space.
- **archetype statements** — a top-level `authorship` member in the framework's own
  archetype documents.

Nothing is required of DM: no URI pattern change for fragment `$ref`s, no widening of
what tenants may `$ref` into the meta, no `DefinitionSubjectType` change, no meta edit,
no migration.

### Negative

- **No cross-estate uniformity.** GSM provides no guaranteed `statement->'authorship'`
  path. The framework layer standardises it for its own ecosystem — the correct scope,
  since that layer is the guarantor.
- **No enforced presence.** No DM rule requires an archetype to carry the facet. This
  becomes a framework-level convention.

Both are losses of guarantees DM was never entitled to make.

## Retained findings

Independent of this decision:

- **`Archetype.schema.json`'s dual role is correct.** Governing schema-document form
  while also being the base archetype for the ARCHETYPE subject type is a fixpoint, not
  a conflation — the termination of the type relation, necessarily asymmetric to its
  siblings. Do not split it. `DefinitionSubjectType.ARCHETYPE = Set.of()` and the uniform
  seal exemption in `validateSchemaComposition` follow from that rather than indicating
  defects.
- **The ascription envelope schema is ungoverned.**
  `AscriptionController.buildAscriptionEnvelope()` is hand-built in Java, hardcoded,
  `additionalProperties: false`, with no Definition, version or lifecycle — an ungoverned
  artifact in a self-describing system. Warrants separate treatment.

## Notes

See the ITIP frameworks foundations for the facet definition and its composition into
the framework bases.
