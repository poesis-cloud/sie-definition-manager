# SIE SDM — Systemic Intelligence Engine (Systemic Data/Definition Models)

This folder contains **architecture/model documentation**, primarily as **PlantUML class diagrams** plus a small amount of **PostgreSQL SQL** that translates parts of the conceptual model.

There is **no runnable application** here (no build/test/debug workflow). The primary developer workflow is: edit diagrams/SQL → render diagrams for review.

## Where to start

- **CSDM (Canonical Systemic Definition Model)**: [concept/model/sie-csdm-v1.puml](concept/model/sie-csdm-v1.puml)
  - Core systemic primitives (System, Interface, Mechanism, Control, etc.)
  - Meta-model constructs (archetypes, schemas, qualifiers)

- **CSDSL (Canonical Systemic Domain Specific Language)**: [concept/model/sie-csdsl-v1.puml](concept/model/sie-csdsl-v1.puml)
  - AST model for logical/comparison expressions used by control conditions

- **SQL translation artifacts**: [concept/model/sql/](concept/model/sql/)
  - Example: qualifiers + schema registry + enforcement trigger in
    [concept/model/sql/sie-csdm-qualifiers-v1.sql](concept/model/sql/sie-csdm-qualifiers-v1.sql)

## Rendering diagrams (PlantUML)

### VS Code (recommended)

- Install a PlantUML extension (commonly `jebbs.plantuml`).
- Use **Local** render.
- A pinned PlantUML jar is available at [tools/plantuml/plantuml.jar](tools/plantuml/plantuml.jar).
- VS Code settings are in [.vscode/settings.json](.vscode/settings.json).

### CLI

Run from this folder (`sie/sie-sdm/`):

```bash
java -jar tools/plantuml/plantuml.jar -tpng concept/**/*.puml
```

If you prefer SVG output:

```bash
java -jar tools/plantuml/plantuml.jar -tsvg concept/**/*.puml
```

Notes:

- The current diagrams are effectively standalone (no `!include`). If you introduce `!include`, make sure include paths resolve both in VS Code and CLI.

## Conventions (important when editing)

- **Versioned files**: diagrams are named `*-v1.puml`. For breaking/semantic changes, prefer creating a `-v2` file instead of rewriting `-v1`.
- **Style preservation**: keep existing `VISUAL THEME` blocks, `frame "… - v1" { ... }` titles, section separators, and note formatting.
- **SQL (PostgreSQL)**:
  - Prefer re-runnable DDL (`create table if not exists`).
  - Commonly wrap changes in `begin; ... commit;`.
  - Expect “conceptual-to-SQL translation” patterns like anchor/supertype tables + triggers for invariants.


# The Canonical Grammar of a Rule
A rule has a much richer grammar than a condition.
A condition recognizes a state; a rule governs behavior by binding recognition to consequence.
If a condition is a receptor, a rule is a governance mechanism.
Below is the clean, canonical grammar of a rule—minimal, systemic, and domain‑agnostic.

The Canonical Grammar of a Rule
A rule is a governance construct that binds:
- Scope — where/what the rule applies to
- Trigger — when the rule becomes eligible
- Condition(s) — what must be true
- Directive / Effect — what must happen
- Context — how the rule is interpreted
- Modifiers — optional refinements (priority, timing, etc.)
This yields the minimal grammar:

Let’s break each component down.

1. Scope (Who/What is Governed)
Defines the domain of applicability.
- an entity (user, resource, mechanism)
- a class/type (all requests, all pods, all invoices)
- a subsystem (security, billing, scheduling)
Examples:
- “For all incoming requests…”
- “For every Kubernetes Pod…”
- “For any financial transaction…”

2. Trigger (When the Rule Activates)
The event or moment that makes the rule eligible for evaluation.
- event-based: on create, on update, on failure
- time-based: every minute, at midnight
- state-based: whenever temperature changes
This is the activation receptor.

3. Conditions (What Must Be True)
The set of state evaluations that must hold for the rule to fire.
Each condition follows the grammar you and I already clarified:
(S,P,R,V,C)
Conditions may be atomic or compound (AND/OR/NOT).

4. Directive / Effect (What the Rule Does)
The action, constraint, or transformation the rule imposes.
This is the effector side.
Examples:
- allow / deny
- mutate / transform
- notify / escalate
- enforce constraint
- update state
- trigger mechanism X
A rule without an effect is not a rule—it’s a check.

5. Context (How to Interpret the Rule)
The semantic environment in which the rule is evaluated.
- domain semantics (security, workflow, physics)
- temporal semantics (now, eventual, continuous)
- actor semantics (system, user, mechanism)
- invariants (units, types, constraints)
Context is often implicit but always present.

6. Modifiers (Optional Governance Metadata)
These refine how the rule behaves:
- priority
- exclusivity
- ordering
- retry policy
- time windows
- severity
- provenance
- versioning
These don’t change the core grammar—they shape execution semantics.

Putting It All Together
A rule is:
A governance mechanism that, when triggered, evaluates conditions within a context and, if satisfied, produces a directive over a defined scope.

This is the universal, cross‑domain definition—from Kubernetes admission controllers to legal contracts to workflow engines to biological regulatory systems.

The Deep Insight (the part that matters for your systemic grammar)
A rule is the governance primitive that binds:
- Receptors (conditions)
- Effectors (directives)
- Mechanism activation (trigger)
- Domain boundaries (scope)
- Interpretation semantics (context)
In your systemic architecture, a rule is the minimal unit of governance causality.
It is the smallest structure that expresses:
- “When X is observed, do Y.”
- “If state matches pattern P, enforce constraint C.”
- “Upon event E, propagate effect F.”
It is the atomic link between state recognition and state transformation.

If you want, I can also give you:
- a formal BNF grammar of rules
- the systemic grammar version aligned with your governance engine
- the mapping to Kubernetes admission webhooks, OPA, or controllers
- the ontology-level definition for your systemic meta-model
- the duality between rule and mechanism in your recursive architecture
Just tell me which direction you want to push.

A policy sits one level above rules.
If a rule is a governance mechanism (“when X, do Y”), then a policy is a governance stance—a structured, declarative statement of intent that constrains, guides, or shapes the rules that implement it.
So its grammar is necessarily more abstract, more normative, and more declarative than a rule.
Below is the clean, canonical grammar of a policy, expressed in a way that aligns with systemic governance, platform architecture, and your own meta‑modeling instincts.

# The Canonical Grammar of a Policy

A policy is a normative governance declaration with the following minimal grammar:
1. Purpose / Intent (Why the policy exists)
The high‑level objective the policy is meant to achieve.
- “Ensure data confidentiality”
- “Guarantee fair resource allocation”
- “Maintain system resilience”
This is the teleology of the policy.

2. Scope (What the policy governs)
The domain, entities, or processes the policy applies to.
- resources
- users
- services
- workflows
- environments
This defines the governance boundary.

3. Principles / Norms (The stance or constraints)
The abstract, non‑operational statements that express what must be upheld.
These are not rules—they are governance invariants.
Examples:
- “Access must be least‑privilege.”
- “Changes must be auditable.”
- “Data must not leave the EU.”
- “Critical operations require dual control.”
This is the normative core of the policy.

4. Directives / Requirements (What must be enforced)
These are the actionable, but still non‑procedural, obligations.
They bridge principles → rules.
Examples:
- “All requests must be authenticated.”
- “All deployments must pass security validation.”
- “All financial transactions above €10k require approval.”
These are rule‑generating constraints.

5. Enforcement Semantics (How the policy is realized)
Defines how the policy is interpreted and enforced, without specifying rules.
Examples:
- mandatory vs advisory
- continuous vs event‑based
- blocking vs monitoring
- automated vs human‑in‑the‑loop
This is the execution posture of the policy.

6. Context (The environment of interpretation)
The domain, legal, organizational, or systemic context.
- regulatory frameworks
- organizational values
- system architecture
- risk model
- temporal context
Context shapes meaning without altering structure.

Putting It All Together
A policy is:
A declarative governance statement that defines intent, scope, and normative constraints, from which rules and mechanisms derive.

Formally:


How a Policy Differs from a Rule (the key systemic distinction)
|  |  |  | 
|  |  |  | 
|  |  |  | 
|  |  |  | 
|  |  |  | 
|  |  |  | 
|  |  |  | 


A policy does not execute.
A rule does.
A policy defines what must be true.
A rule defines how it is enforced.

The Deep Insight (the part that matters for your systemic grammar)
A policy is the governance definition layer.
A rule is the governance realization layer.
A mechanism is the governance execution layer.
In your systemic architecture:
- Policies define normative constraints
- Rules define conditional causal links
- Mechanisms define state‑transforming operations
This gives you a clean, recursive separation:
Policy → Rule → Mechanism → State
And the grammar of each layer is distinct, minimal, and composable.

If you want, I can also give you:
- a formal BNF grammar for policies
- the systemic meta‑model version aligned with your governance engine
- the mapping to Kubernetes policies (OPA, Kyverno, Gatekeeper)
- the duality between policy and invariant
- the policy → rule compilation pipeline in a systemic architecture
Just tell me where you want to take it next.

# Articulation of Policies and Rules

To articulate policies and rules in a single grammar, you need a structure that preserves their different roles while expressing them within one coherent, layered syntax.
The key is this:
- A policy is declarative (intent, principles, constraints).
- A rule is operational (trigger, conditions, effect).
- A unified grammar must treat a rule as a realization of a policy, not as a sibling.
The cleanest way to do this is to define a two‑layer grammar where:
- Policies define governance constraints
- Rules implement those constraints
But both share a common structural backbone.
Below is the minimal, elegant unified grammar.

Unified Grammar for Policies and Rules
1. Governance Artifact
Everything—policy or rule—is a governance artifact with a shared top‑level structure:
GovernanceArtifact = (ID, Scope, Context, Semantics, Body)
Where:
- ID — unique identifier
- Scope — what it applies to
- Context — domain, temporal, organizational semantics
- Semantics — mandatory/advisory, blocking/monitoring, etc.
- Body — the part that differs between policy and rule
This gives you a single envelope.

2. Policy Body
A policy body is declarative:
PolicyBody = (Intent, Principles, Directives)
- Intent — why the policy exists
- Principles — normative constraints
- Directives — high‑level obligations that must be enforced
No triggers. No conditions. No effects.
A policy does not execute.

3. Rule Body
A rule body is operational:
RuleBody = (Trigger, Conditions, Effect)
- Trigger — when the rule activates
- Conditions — what must be true
- Effect — what the rule does
A rule is executable governance logic.

4. Unified Grammar
Putting it all together:
GovernanceArtifact ::= Policy | Rule

Policy ::= 
    ID
    Scope
    Context
    Semantics
    PolicyBody

PolicyBody ::= 
    Intent
    Principles
    Directives

Rule ::= 
    ID
    Scope
    Context
    Semantics
    RuleBody

RuleBody ::= 
    Trigger
    Conditions
    Effect


This is the simplest possible unified grammar that:
- preserves the semantic distinction
- maintains a shared structural envelope
- allows policies to generate rules
- allows rules to trace back to policies
- supports recursive governance (policies governing rules, rules enforcing policies)

5. The Deep Insight (the part that matters for your systemic architecture)
The unification works because:
- Policy = definition layer
- Rule = realization layer
- Both are governance artifacts with shared metadata
- Their bodies differ because their roles differ
This gives you a clean, composable hierarchy:
Policy → Rule → Mechanism → State
And a single grammar that expresses all of them without collapsing their identities.

If you want, I can also give you:
- a fully formal BNF version
- a meta‑model diagram
- a canonical ontology for governance artifacts
- the compilation pipeline from policy → rule → mechanism
- the mapping to Kubernetes (OPA, Kyverno, Gatekeeper)
Just tell me how deep you want to go.
