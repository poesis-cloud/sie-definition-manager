# ADR — Uniform polymorphic Ascription API

**Status:** accepted

## Context

DM exposes a single, uniform REST surface for all ascriptions. Subject type is not
carried in the URL; it is derived from the referenced archetype. Lifecycle status is
carried in the request body rather than addressed as a distinct resource.

A review of authorization granularity questioned this. Because authn/authz are held by
an API gateway in the deployment target, and because that gateway's policy is expressed
as HTTP verb over HTTP resource, a uniform surface means the URL carries no semantics
the gateway can act on. Two distinctions in particular are invisible to it:

- **Subject type.** Creating a Structure and creating an Archetype are the same URL
  shape, yet carry very different authority — an Archetype defines the type system
  against which all subsequent ascriptions are validated.
- **Lifecycle transition.** `DRAFT -> ACTIVE`, `ACTIVE -> DEPRECATED` and `-> RETIRED`
  carry very different authority, yet are the same verb on the same resource. A
  body-blind policy collapses the entire governance state machine into one permission.

Alternatives considered were per-subject-type paths (`POST /structures`,
`POST /archetypes`), transitions as sub-resources
(`POST /archetypes/{id}/activate`), and a hybrid lifting the discriminator into a path
segment (`POST /ascriptions/{subjectType}/...`).

## Decision

**Keep the uniform polymorphic surface unchanged.** Subject type continues to be
derived from the archetype, not the path. Lifecycle status continues to be carried in
the request body.

DM implements no authorization. Authorization remains external. Where the gateway's
current verb-over-resource model is insufficient to express a needed distinction, the
gateway model is revised — not the API.

## Rationale

The decisive arguments are properties of the interface itself, independent of the
implementation behind it.

### Polymorphic where the domain is polymorphic

`POST /ascriptions` with an `archetypeId` mirrors the model exactly: everything is an
ascription, and typing comes from the archetype rather than the endpoint. A client
integrates once and handles any subject type without branching. Per-type paths would
require every client to know the eight GSM primitives before it can do anything.

### Extensibility without API change

The surface is unchanged if the set of subject types ever changes. Encoding the eight
primitives into the URL space would make a model change a breaking API change.

### Consistency with the self-describing premise

Clients discover what is expressible by reading archetypes, not by reading path
documentation. The surface stays small and constant while expressible content grows.
Splitting the API by subject type would move knowledge out of the data and into the URL
space — the inversion this system exists to avoid.

### Uniform client contracts

One request shape, one error shape, one lifecycle idiom, one filter syntax across
everything. Generic tooling — browsers, sync agents, bulk loaders — works without
per-type cases.

### Authorization is not an API-shape concern

Shaping the URL space to suit a particular external policy engine would let an
infrastructure concern dictate the domain interface. Who may activate a governing
archetype is a governance question, not a routing question.

## Consequences

### The gateway must interpret request bodies

This is the principal cost, and it is accepted deliberately.

Any policy that needs to distinguish subject type or lifecycle transition must read the
request body. A verb-over-resource gateway cannot do this. Concretely, the gateway must
be able to:

- resolve the referenced archetype to its subject type, or read the subject type from
  the payload, in order to distinguish archetype authoring from instance authoring;
- read the requested lifecycle status in order to distinguish transitions from one
  another;
- apply the tenant claim as a scoping decision, since tenant isolation is data-scoped
  and not expressible as a path.

This requires a policy-aware gateway rather than a path-matching one. Coarse
verb-over-resource rules — for example `defman:read` versus `defman:write` — remain
usable as a ceiling, but cannot express governance-level distinctions on their own.

### Any URL-based policy layer is equally blind

The consequence is not specific to authorization. Rate limiting, routing, audit rules
and caching are all blind to what is being done when the URL carries no semantics. Each
such layer must either interpret bodies or operate at coarse granularity.

### Property retained

The API surface is insulated from model evolution. New subject types, new archetypes and
new tenant extensions require no endpoint changes and no client changes.

### Rejected consequence

The hybrid (`POST /ascriptions/{subjectType}/...`,
`.../{id}/transitions/{transition}`) would give URL-legible semantics at the cost of the
extensibility property above: the discriminator would become part of the URL contract.
This remains available if the gateway proves unable to interpret bodies in practice, but
is not adopted now.

## Notes

Authorship of ascriptions is out of scope for DM and GSM; it is carried by client
frameworks. See the ITIP frameworks foundations.
