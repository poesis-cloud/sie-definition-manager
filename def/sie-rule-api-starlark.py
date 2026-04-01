# ================================================================
# SIE Rule API — v2 — Starlark Binding
# ================================================================
#
# Normative contract: ALL constructs available to Rule authors.
# This file IS valid Python (Starlark is a Python subset).
#
# ================================================================
# ARCHITECTURE
# ================================================================
#
#   ┌─────────────────────────────────────────────────────┐
#   │  Starlark sandbox                                   │
#   │                                                     │
#   │  1. sys → SystemContext (fluent API)                │
#   │  2. Injected functions (4 host-provided)            │
#   │  3. Starlark built-ins (Python subset)              │
#   │                                                     │
#   │  Nothing else.                                      │
#   └─────────────────────────────────────────────────────┘
#
# sys.receive() declares the trigger and returns the event payload.
# sys.effect() returns a fluent builder. Chained methods
# (.by, .receive, .on) qualify port archetypes.
# The runtime handles transactional boundaries.
#
# ================================================================
# EXECUTION PIPELINE
# ================================================================
#
# 1. MATCH    — Find Mechanisms whose sys.receive() Archetype matches.
# 2. INJECT   — Bind sys (with event + mechanism), inject 4 functions.
# 3. EXECUTE  — Run rule body (Starlark). sys.effect() chains
#               execute synchronously and return results.
# 4. COMPLETE — Mark execution done. Trigger downstream Mechanisms
#               from emitted events.
#
# Note: sys.receive() is parsed at SAVE time (Definition Manager
# extracts the Archetype name and stores Mechanism → Archetype as
# a derived relationship). At runtime, sys.receive() returns the
# matched event payload.
#
# ================================================================

from typing import Any, Optional

# ================================================================
# 1. SYSTEM CONTEXT — the fluent effect API
# ================================================================
#
# sys is the ONLY stateful object in the sandbox.
# Rule authors access it as a global named "sys".
#
# sys.receive() is the trigger declaration (sensor).
# sys.effect() is the single output primitive (actuator).
# Archetype semantics (create, modify, delete, emit, acquire, etc.)
# are defined by the Archetype itself — not by the function name.
# The Archetype IS the contract.
#
# GSM ports are AUTO-DERIVED from the fluent chains (see §4).
#
# Starlark host: @StarlarkBuiltin(name = "sys")
# ================================================================

class SystemContext:
    """Rule execution context: system identity + fluent API."""

    # --- Read-only properties ---

    id: str
    """The Mechanism's id (UUIDv7, RFC 9562)."""

    # --- Receive (trigger declaration — sensor) ---

    def receive(self, event_archetype: str) -> "ReceptorBuilder":
        """
        Declare the triggering Archetype and return its payload.
        MUST be the first executable statement in the body.
        MUST be called exactly once.
        Argument MUST be a string literal.

        At save time, the Definition Manager extracts the argument
        and stores it as a derived relationship:
          Mechanism → Archetype (triggeredBy)

        At runtime, returns the event payload that triggered this
        Mechanism's rule (the event matched by the pipeline).

        Args:
            event_archetype: String literal — Archetype name.

        Returns:
            ReceptorBuilder — fluent builder for optional trigger
            port qualification. At runtime, resolves to the
            triggering event payload dict.

        Fluent chain methods:
            .on("ReceptorArch")   — qualify trigger Receptor port type

        Valid chain patterns:
            sys.receive("A")
            sys.receive("A").on("R")

        Examples:
            event = sys.receive("PaymentFailed")
            order_id = event["orderId"]

            # With typed trigger Receptor port
            event = sys.receive("PaymentFailed").on("PaymentReceiver")

        GSM auto-derivation:
            sys.receive("A") → Receptor (data Archetype "A",
                                port type: base ReceptorArchetype)
            sys.receive("A").on("R") → Receptor (data Archetype "A",
                                        port type: Archetype "R")
        """
        ...

    # --- Effect (single output primitive — actuator) ---

    def effect(self,
               archetype: str,
               data: Optional[dict[str, Any]] = None) -> "EffectorBuilder":
        """
        Produce an effect typed by the named Archetype.

        This is the SINGLE output primitive. What the effect means
        (create, modify, delete, emit, acquire, etc.) is defined by
        the Archetype, not by the function name.

        Args:
            archetype: String literal — data Archetype name.
            data:      Optional payload dict (must conform to
                       archetype schema).

        Returns:
            EffectorBuilder — fluent builder for optional port
            qualification and feedback declaration.

            When .receive() is NOT chained: returns None at runtime
            (fire-and-forget).
            When .receive() IS chained: returns the feedback payload
            dict at runtime (closed-loop).

        Fluent chain methods:
            .by("EffectorArch")   — qualify Effector port type
            .receive("FeedArch")  — declare feedback (closed-loop)
            .on("ReceptorArch")   — qualify Receptor port type

        Valid chain patterns:
            sys.effect("A", data)
            sys.effect("A", data).by("E")
            sys.effect("A", data).receive("F")
            sys.effect("A", data).receive("F").on("R")
            sys.effect("A", data).by("E").receive("F")
            sys.effect("A", data).by("E").receive("F").on("R")

        Examples:
            # Fire-and-forget (base Effector)
            sys.effect("OrderShipped", {"orderId": oid, "carrier": "DHL"})

            # Typed Effector port
            sys.effect("OrderShipped", {"orderId": oid}).by("LogisticsGateway")

            # Feedback (closed-loop, base ports)
            result = (sys.effect("ValidatePayment", {"orderId": oid})
                .receive("PaymentValidated"))
            if result["approved"]:
                sys.effect("OrderUpdate", {"id": oid, "status": "paid"})

            # Full typing (typed Effector + typed feedback Receptor)
            result = (sys.effect("ValidatePayment", {"orderId": oid})
                .by("PaymentGateway")
                .receive("PaymentValidated")
                .on("PaymentReceiver"))

        GSM auto-derivation:
            sys.effect("A", data) → Effector (data Archetype "A",
                                    port type: base EffectorArchetype)
        """
        ...

class ReceptorBuilder:
    """Fluent builder returned by sys.receive(). Qualifies trigger port."""

    def on(self, receptor_archetype: str) -> "ReceptorBuilder":
        """
        Qualify the trigger Receptor port with a specific Archetype.

        Args:
            receptor_archetype: String literal — Receptor Archetype name.
                                Defaults to base ReceptorArchetype when
                                omitted.

        Returns:
            self (fluent chaining). The builder still returns the
            event payload dict at runtime.

        GSM auto-derivation:
            .on("R") → trigger Receptor port typed by Archetype "R"
                       (instead of base ReceptorArchetype).
        """
        ...


class EffectorBuilder:
    """Fluent builder returned by sys.effect(). Qualifies ports."""

    def by(self, effector_archetype: str) -> "EffectorBuilder":
        """
        Qualify the Effector port with a specific Archetype.

        Args:
            effector_archetype: String literal — Effector Archetype name.
                                Defaults to base EffectorArchetype when
                                omitted.

        Returns:
            self (fluent chaining).

        GSM auto-derivation:
            .by("E") → Effector port typed by Archetype "E"
                       (instead of base EffectorArchetype).
        """
        ...

    def receive(self, feedback_archetype: str) -> "EffectorBuilder":
        """
        Declare a feedback Receptor for the effect (closed-loop).

        Args:
            feedback_archetype: String literal — data Archetype name
                                for the feedback payload.

        Returns:
            self (fluent chaining). The chain now returns the
            feedback payload dict at runtime instead of None.

        GSM auto-derivation:
            .receive("F") → Receptor (typed by data Archetype "F").
        """
        ...

    def on(self, receptor_archetype: str) -> "EffectorBuilder":
        """
        Qualify the Receptor port with a specific Archetype.
        MUST follow .receive() (qualifies the feedback Receptor).

        Args:
            receptor_archetype: String literal — Receptor Archetype name.
                                Defaults to base ReceptorArchetype when
                                omitted.

        Returns:
            self (fluent chaining).

        GSM auto-derivation:
            .on("R") → Receptor port typed by Archetype "R"
                       (instead of base ReceptorArchetype).
        """
        ...

# ================================================================
# 2. INJECTED FUNCTIONS (4 host-provided)
# ================================================================
#
# Injected because Starlark sandbox lacks: clock, randomness, regex.
#
# All other computation is native Starlark:
#   sum       → total = 0; for x in vals: total += x
#   avg       → total / len(vals)
#   coalesce  → a if a != None else b  (or dict.get(key, default))
#   intersect → [x for x in a if x in b]
#   union     → a + [x for x in b if x not in a]
#   difference→ [x for x in a if x not in b]
#   distinct  → list({x: True for x in vals})
#
# APIs follow Python conventions.
# ================================================================

def now(fmt: Optional[str] = None) -> str:
    """
    Current UTC datetime as formatted string.
    Python equivalent: datetime.datetime.now(utc).strftime(fmt) / .isoformat()

    Args:
        fmt: strftime format (None → ISO8601).

    Examples:
        now()              # "2026-02-21T14:30:00.000Z"
        now("%Y-%m-%d")    # "2026-02-21"

    NON-DETERMINISTIC. MUST NOT appear in Policy constraints.
    """
    ...

def uuid7() -> str:
    """
    RFC 9562 UUIDv7 (time-sortable, Unix epoch ms + random).

    Examples:
        sys.effect("Incident", {"id": uuid7(), ...})

    NON-DETERMINISTIC. MUST NOT appear in Policy constraints.
    """
    ...

def fullmatch(pattern: str, string: str) -> bool:
    """
    Test if entire string matches RE2 regex.
    Python equivalent: bool(re.fullmatch(pattern, string))

    Examples:
        fullmatch(r"[a-z]+[0-9]+", "abc123")  # True
    """
    ...

def search(pattern: str, string: str) -> Optional[str]:
    """
    First capture group from RE2 regex search.
    Python equivalent: re.search(pattern, string).group(1)

    Returns None if no match.

    Examples:
        search(r"v([0-9.]+)", "app-v2.3.1")  # "2.3.1"
    """
    ...

# ================================================================
# 3. STARLARK BUILT-IN CAPABILITIES (native, not injected)
# ================================================================
#
# 3.1 Functions: len, min, max, range, sorted, reversed,
#     enumerate, zip, int, float, str, bool, list, dict,
#     tuple, type, repr, hash, getattr, hasattr, any, all
#
# 3.2 String methods: startswith, endswith, find, index, count,
#     lower, upper, strip, lstrip, rstrip, split, splitlines,
#     join, replace, format, title, capitalize,
#     isalpha, isdigit, isalnum, isspace, "sub" in s
#
# 3.3 List methods: append, extend, insert, remove, pop, index,
#     clear, x in list, x not in list
#
# 3.4 Dict methods: get, keys, values, items, pop, update,
#     setdefault, key in dict
#
# 3.5 Comprehensions:
#     [expr for x in list]
#     [expr for x in list if cond]
#     {k: v for x in list}
#     any(pred for x in list)
#     all(pred for x in list)
#     [expr for x in list1 for y in list2]
#
# 3.6 Control flow: if/elif/else, for, break, continue,
#     def (local functions), return
#     NO: while, try/except, class, import/load
#
# 3.7 Operators: + - * / // %  == != < > <= >=
#     and or not  in  not in  x[i] x[i:j]  = += -= *= //= %=
#     NO: ** (exponent), bitwise (&|^~<<>>)
#     NOTE: / is integer division; use float(a)/b for true division.
#
# ================================================================

# ================================================================
# 4. GSM AUTO-DERIVATION
# ================================================================
#
# On save, the Definition Manager parses the Starlark AST and
# auto-derives GSM ports (Effectors and Receptors). Mechanism
# rule authors never see or manage these — the grammar is
# port-free.
#
# TRIGGER (INPUT)
# ----------------
# sys.receive("A") declares the triggering Archetype and returns
# the event payload. Optional .on("R") qualifies the trigger
# Receptor port type.
#
# OPEN-LOOP vs CLOSED-LOOP PATTERN (OUTPUT)
# ------------------------------------------
# Closed-loop is declared explicitly with .receive() in the
# effect fluent chain. If .receive() is present, the DM
# auto-derives a feedback Receptor (closed-loop). If absent,
# only an Effector is derived (fire-and-forget — open-loop).
# Determined by static AST analysis of the effect chain.
#
# PORT ARCHETYPE QUALIFICATION
# ----------------------------
# .by() and .on() qualify the port archetype (tenant-defined
# specialization of EffectorArchetype / ReceptorArchetype).
# When omitted, the base EffectorArchetype / ReceptorArchetype
# is used.
#
# ┌──────────────────────────────────────────┬──────────────────────────────────────────┐
# │ Starlark construct                       │ GSM primitives auto-derived              │
# ├──────────────────────────────────────────┼──────────────────────────────────────────┤
# │ sys.receive("A")                         │ Receptor (data Arch "A",                 │
# │                                          │          port: base ReceptorArchetype)   │
# │                                          │                                          │
# │ sys.receive("A").on("R")                 │ Receptor (data Arch "A",                 │
# │                                          │          port: Archetype "R")            │
# │                                          │                                          │
# │ sys.effect("A", data)                    │ Effector (data Arch "A",                 │
# │                                          │          port: base EffectorArchetype)   │
# │                                          │                                          │
# │ sys.effect("A", data).by("E")            │ Effector (data Arch "A",                 │
# │                                          │          port: Archetype "E")            │
# │                                          │                                          │
# │ sys.effect("A", data).receive("F")       │ Effector (data Arch "A",                 │
# │                                          │          port: base EffectorArchetype)   │
# │                                          │ + Receptor (data Arch "F",               │
# │                                          │            port: base ReceptorArchetype) │
# │                                          │                                          │
# │ sys.effect("A", data).receive("F")       │ Effector (data Arch "A",                 │
# │   .on("R")                               │          port: base EffectorArchetype)   │
# │                                          │ + Receptor (data Arch "F",               │
# │                                          │            port: Archetype "R")          │
# │                                          │                                          │
# │ sys.effect("A", data).by("E")            │ Effector (data Arch "A",                 │
# │   .receive("F").on("R")                  │          port: Archetype "E")            │
# │                                          │ + Receptor (data Arch "F",               │
# │                                          │            port: Archetype "R")          │
# └──────────────────────────────────────────┴──────────────────────────────────────────┘
#
# Auto-derived ports carry: derived_from_mechanism_id, derived=true.
# Recomputed on rule change (body_hash diff → delete old, derive new).
#
# ================================================================

# ================================================================
# 5. VALIDATION (on save)
# ================================================================
#
# 1. SYNTAX: StarlarkFile.parse() succeeds.
# 2. SYS.RECEIVE() DECLARATION: Body begins with exactly one
#    sys.receive("...") call. Argument is a string literal
#    resolving to a defined Archetype in the governed scope.
#    Optional .on("R") qualifies the trigger Receptor port.
# 3. ARCHETYPE RESOLUTION: String literals in sys.receive() and
#    sys.effect() chains resolve to declared Archetypes in the
#    governed scope.
# 4. SCHEMA CONFORMANCE: Dict literal in sys.effect() (second arg)
#    must match target archetype schema (best-effort for dynamic).
# 5. STRING LITERALS ONLY: All archetype arguments in fluent
#    chains must be string literals (enables static analysis +
#    auto-derivation).
# 6. EFFECT CHAIN ORDER: Valid order is
#    effect → [by] → [receive → [on]]. Each method appears at
#    most once; no other methods allowed on the chain.
#    RECEIVE CHAIN ORDER: receive → [on].
# 7. EFFECT CHAIN ARITY: sys.effect(archetype [, data]) requires
#    1-2 args. sys.receive(archetype) requires exactly 1 arg.
#    .by(), .receive(), .on() each require exactly 1 arg.
# 8. ALLOWED GLOBALS: Only sys, 4 injected functions (now,
#    uuid7, fullmatch, search), and Starlark built-ins. Any other
#    global → error.
# 9. NO load() STATEMENTS.
# 10. EXECUTION BUDGET: max steps (default 100k, configurable).
#
# ================================================================

# ================================================================
# 6. EXAMPLE
# ================================================================
#
#   event = sys.receive("PaymentFailed")
#
#   # Acquire order data (fire-and-forget, base Effector)
#   order = sys.effect("AcquireOrder",
#       {"id": event["orderId"]})
#
#   # Update order status (typed Effector)
#   sys.effect("OrderUpdate", {
#       "id": event["orderId"],
#       "status": "payment_failed",
#       "failureReason": event["reason"],
#       "updatedAt": now()
#   }).by("OrderManager")
#
#   if order["amount"] > 10000:
#       # Fire-and-forget notification (base Effector)
#       sys.effect("HighValueOrderFlagged", {
#           "orderId": event["orderId"],
#           "reason": event["reason"],
#           "amount": order["amount"],
#           "detectedAt": now()
#       })
#
#       # Closed-loop: request fraud assessment (full typing)
#       assessment = (sys.effect("FraudCheckRequested", {
#               "orderId": event["orderId"],
#               "amount": order["amount"]})
#           .by("FraudGateway")
#           .receive("FraudCheckCompleted")
#           .on("FraudReceiver"))
#
#       if assessment["riskScore"] > 0.8:
#           sys.effect("OrderHold", {
#               "id": event["orderId"],
#               "fraudHold": True,
#               "riskScore": assessment["riskScore"]
#           })
#
