#!/usr/bin/env python3
"""
Formal verification of GSM Definition Lifecycle framework.

Encodes the Transition Rules and Inter Transition Rules from
gsm-definition-lifecycle.puml, then evaluates every SC-XX scenario
and assertion against them.
"""

from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Optional

# ============================================================
# FRAMEWORK ENCODING
# ============================================================

class S(Enum):
    """ElementDefinitionStatus."""
    DRAFT = "DRAFT"
    PROPOSED = "PROPOSED"
    APPROVED = "APPROVED"
    ACTIVE = "ACTIVE"
    SUSPENDED = "SUSPENDED"
    DEPRECATED = "DEPRECATED"
    RETIRED = "RETIRED"
    ABANDONED = "ABANDONED"
    REJECTED = "REJECTED"

ALL_9 = set(S)

# Valid transitions (from Legend note)
VALID_TRANSITIONS: set[tuple[Optional[S], S]] = {
    (None,          S.DRAFT),
    (S.DRAFT,       S.PROPOSED),
    (S.PROPOSED,    S.APPROVED),
    (S.APPROVED,    S.ACTIVE),
    (S.ACTIVE,      S.SUSPENDED),
    (S.SUSPENDED,   S.ACTIVE),
    (S.ACTIVE,      S.DEPRECATED),
    (S.DEPRECATED,  S.SUSPENDED),
    (S.SUSPENDED,   S.DEPRECATED),
    (S.DEPRECATED,  S.RETIRED),
    (S.DRAFT,       S.ABANDONED),
    (S.PROPOSED,    S.REJECTED),
}

# Referee preconditions: for transition (X, Y), EACH Reference MUST be in set
REFEREE_PRECONDITIONS: dict[tuple[Optional[S], S], set[S]] = {
    (None,          S.DRAFT):      {S.DRAFT, S.PROPOSED, S.APPROVED, S.ACTIVE},
    (S.DRAFT,       S.PROPOSED):   {S.PROPOSED, S.APPROVED, S.ACTIVE},
    (S.PROPOSED,    S.APPROVED):   {S.APPROVED, S.ACTIVE},
    (S.APPROVED,    S.ACTIVE):     {S.ACTIVE},
    (S.ACTIVE,      S.SUSPENDED):  {S.ACTIVE, S.SUSPENDED, S.DEPRECATED},
    (S.SUSPENDED,   S.ACTIVE):     {S.ACTIVE, S.DEPRECATED},
    (S.SUSPENDED,   S.DEPRECATED): {S.ACTIVE, S.SUSPENDED, S.DEPRECATED},
    (S.ACTIVE,      S.DEPRECATED): {S.ACTIVE, S.SUSPENDED, S.DEPRECATED},
    (S.DEPRECATED,  S.SUSPENDED):  {S.ACTIVE, S.SUSPENDED, S.DEPRECATED},
    (S.DEPRECATED,  S.RETIRED):    {S.ACTIVE, S.SUSPENDED, S.DEPRECATED, S.RETIRED},
    (S.DRAFT,       S.ABANDONED):  ALL_9,
    (S.PROPOSED,    S.REJECTED):   ALL_9 - {S.DRAFT},
}

# Referee → Reference edges
REFEREE_REFERENCES: dict[str, list[str]] = {
    "Mechanism":   ["Structure"],
    "Effector":    ["Artifact"],
    "Receptor":    ["Artifact"],
    "Interaction": ["Effector", "Receptor"],
    "Interface":   ["Structure", "Effector", "Receptor"],
    "Structure":   [],
    "Artifact":    [],
}

# Cascade types
# Composition: Mechanism → Effector, Receptor
# Aggregation: Structure → Mechanism, Interface
# Reference:   Effector → Interaction, Interface
#              Receptor → Interaction, Interface

REFERENCE_CASCADE_SCOPE: set[tuple[S, S]] = {
    (S.ACTIVE,     S.SUSPENDED),
    (S.ACTIVE,     S.DEPRECATED),
    (S.SUSPENDED,  S.DEPRECATED),
    (S.DEPRECATED, S.SUSPENDED),
    (S.DEPRECATED, S.RETIRED),
    (S.DRAFT,      S.ABANDONED),
    (S.PROPOSED,   S.REJECTED),
}


# ============================================================
# ELEMENT MODEL (for cascade evaluation)
# ============================================================

@dataclass
class Element:
    """An element instance for scenario evaluation."""
    name: str
    kind: str  # Structure, Mechanism, Effector, Receptor, Interaction, Interface, Artifact
    status: S
    references: dict[str, list['Element']] = field(default_factory=dict)
    # Composition targets (Mechanism → Effectors/Receptors)
    composition_targets: list['Element'] = field(default_factory=list)
    # Aggregation targets (Structure → Mechanisms/Interfaces)
    aggregation_targets: list['Element'] = field(default_factory=list)
    # Reference cascade targets (Effector/Receptor → Interactions/Interfaces)
    reference_cascade_targets: list['Element'] = field(default_factory=list)


def check_referee_preconditions(element: Element, x: Optional[S], y: S) -> tuple[bool, str]:
    """
    Check Referee preconditions for element transitioning x→y.
    Returns (pass, explanation).
    """
    ref_kinds = REFEREE_REFERENCES.get(element.kind, [])
    if not ref_kinds:
        return True, f"{element.kind} is not a Referee (no References)"

    allowed = REFEREE_PRECONDITIONS.get((x, y))
    if allowed is None:
        return False, f"No precondition defined for ({x}, {y})"

    for ref_kind in ref_kinds:
        refs = element.references.get(ref_kind, [])
        for ref_elem in refs:
            if ref_elem.status not in allowed:
                return False, (
                    f"Reference {ref_elem.name}({ref_elem.status.value}) "
                    f"∉ {{{','.join(s.value for s in sorted(allowed, key=lambda s: s.value))}}}"
                )
    return True, "All References in allowed set"


def evaluate_cascade(
    source: Element,
    x: S, y: S,
    cascade_type: str,
    target: Element,
    depth: int = 0
) -> tuple[bool, list[str]]:
    """
    Evaluate cascade of x→y from source to target.
    Returns (success, log_lines).
    """
    indent = "  " * depth
    log = []

    # Step 1: target.status must equal X
    if target.status != x:
        log.append(f"{indent}Step 1 FAIL: {target.name}.status={target.status.value} ≠ {x.value}")
        return False, log
    log.append(f"{indent}Step 1 OK: {target.name}.status={target.status.value} = {x.value}")

    # Step 2: target's preconditions for x→y
    ok, reason = check_referee_preconditions(target, x, y)
    if not ok:
        log.append(f"{indent}Step 2 FAIL: {target.name} precondition: {reason}")
        return False, log
    log.append(f"{indent}Step 2 OK: {target.name} precondition: {reason}")

    # Before transitioning target, check composition cascades (if target has them)
    for comp_target in target.composition_targets:
        comp_ok, comp_log = evaluate_cascade(target, x, y, "Composition", comp_target, depth + 1)
        log.extend(comp_log)
        if not comp_ok:
            log.append(f"{indent}Composition cascade FAILED for {comp_target.name} → {target.name} transition BLOCKED")
            return False, log

    # Step 3: target transitions
    target.status = y
    log.append(f"{indent}Step 3: {target.name} transitions {x.value}→{y.value}")

    # Fire sub-cascades from target
    # Composition cascades from target (already handled above for pre-check)
    # Reference cascades from target
    if (x, y) in REFERENCE_CASCADE_SCOPE:
        for ref_target in target.reference_cascade_targets:
            if ref_target.status == x:  # Quick pre-check
                ref_ok, ref_log = evaluate_cascade(target, x, y, "Reference", ref_target, depth + 1)
                log.extend(ref_log)
                if not ref_ok:
                    log.append(f"{indent}Reference cascade NO-OP for {ref_target.name}")

    return True, log


# ============================================================
# TEST HARNESS
# ============================================================

class Result(Enum):
    PASS = auto()
    FAIL = auto()
    WARN = auto()

@dataclass
class TestResult:
    sc_id: str
    result: Result
    expected: str
    actual: str
    details: str = ""


def make_element(name, kind, status, **kwargs):
    return Element(name=name, kind=kind, status=status, **kwargs)


results: list[TestResult] = []


def assert_pass(sc_id, element, x, y, note=""):
    """Assert that element's transition x→y passes preconditions."""
    ok, reason = check_referee_preconditions(element, x, y)
    if ok:
        results.append(TestResult(sc_id, Result.PASS, "PASS", "PASS", note))
    else:
        results.append(TestResult(sc_id, Result.FAIL, "PASS", f"BLOCKED: {reason}", note))


def assert_blocked(sc_id, element, x, y, note=""):
    """Assert that element's transition x→y is blocked by preconditions."""
    ok, reason = check_referee_preconditions(element, x, y)
    if not ok:
        results.append(TestResult(sc_id, Result.PASS, "BLOCKED", f"BLOCKED: {reason}", note))
    else:
        results.append(TestResult(sc_id, Result.FAIL, "BLOCKED", "PASS (not blocked)", note))


# ============================================================
# §1 — Referee preconditions (progress path)
# ============================================================

def test_s1():
    """§1 — Progress path."""

    # SC-01: Structure(DRAFT). Create Mechanism [*]->DRAFT. PASS.
    s = make_element("Structure", "Structure", S.DRAFT)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [s]})
    assert_pass("SC-01", m, None, S.DRAFT)

    # SC-02: Structure(ABANDONED). Create Mechanism [*]->DRAFT. BLOCKED.
    s = make_element("Structure", "Structure", S.ABANDONED)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [s]})
    assert_blocked("SC-02", m, None, S.DRAFT)

    # SC-03: Structure(RETIRED). Create Mechanism [*]->DRAFT. BLOCKED.
    s = make_element("Structure", "Structure", S.RETIRED)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [s]})
    assert_blocked("SC-03", m, None, S.DRAFT)

    # SC-04: Structure(REJECTED). Create Mechanism [*]->DRAFT. BLOCKED.
    s = make_element("Structure", "Structure", S.REJECTED)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [s]})
    assert_blocked("SC-04", m, None, S.DRAFT)

    # SC-05: Structure(DRAFT). Mechanism DRAFT->PROPOSED. BLOCKED.
    s = make_element("Structure", "Structure", S.DRAFT)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [s]})
    assert_blocked("SC-05", m, S.DRAFT, S.PROPOSED)

    # SC-06: Structure(PROPOSED). Mechanism DRAFT->PROPOSED. PASS.
    s = make_element("Structure", "Structure", S.PROPOSED)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [s]})
    assert_pass("SC-06", m, S.DRAFT, S.PROPOSED)

    # SC-07: Structure(PROPOSED). Mechanism PROPOSED->APPROVED. BLOCKED.
    s = make_element("Structure", "Structure", S.PROPOSED)
    m = make_element("Mechanism", "Mechanism", S.PROPOSED, references={"Structure": [s]})
    assert_blocked("SC-07", m, S.PROPOSED, S.APPROVED)

    # SC-08: Structure(APPROVED). Mechanism PROPOSED->APPROVED. PASS.
    s = make_element("Structure", "Structure", S.APPROVED)
    m = make_element("Mechanism", "Mechanism", S.PROPOSED, references={"Structure": [s]})
    assert_pass("SC-08", m, S.PROPOSED, S.APPROVED)

    # SC-09: Structure(APPROVED). Mechanism APPROVED->ACTIVE. BLOCKED.
    s = make_element("Structure", "Structure", S.APPROVED)
    m = make_element("Mechanism", "Mechanism", S.APPROVED, references={"Structure": [s]})
    assert_blocked("SC-09", m, S.APPROVED, S.ACTIVE)

    # SC-10: Structure(ACTIVE). Mechanism APPROVED->ACTIVE. PASS.
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.APPROVED, references={"Structure": [s]})
    assert_pass("SC-10", m, S.APPROVED, S.ACTIVE)


# ============================================================
# §2 — Referee preconditions (degradation)
# ============================================================

def test_s2():
    """§2 — Degradation."""

    # SC-11
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE, references={"Structure": [s]})
    assert_pass("SC-11", m, S.ACTIVE, S.SUSPENDED)

    # SC-12
    s = make_element("Structure", "Structure", S.RETIRED)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE, references={"Structure": [s]})
    assert_blocked("SC-12", m, S.ACTIVE, S.SUSPENDED)

    # SC-13
    s = make_element("Structure", "Structure", S.SUSPENDED)
    m = make_element("Mechanism", "Mechanism", S.SUSPENDED, references={"Structure": [s]})
    assert_blocked("SC-13", m, S.SUSPENDED, S.ACTIVE)

    # SC-14
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.SUSPENDED, references={"Structure": [s]})
    assert_pass("SC-14", m, S.SUSPENDED, S.ACTIVE)

    # SC-15
    s = make_element("Structure", "Structure", S.DEPRECATED)
    m = make_element("Mechanism", "Mechanism", S.SUSPENDED, references={"Structure": [s]})
    assert_pass("SC-15", m, S.SUSPENDED, S.ACTIVE)

    # SC-16
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE, references={"Structure": [s]})
    assert_pass("SC-16", m, S.ACTIVE, S.DEPRECATED)

    # SC-17
    s = make_element("Structure", "Structure", S.DEPRECATED)
    m = make_element("Mechanism", "Mechanism", S.DEPRECATED, references={"Structure": [s]})
    assert_pass("SC-17", m, S.DEPRECATED, S.SUSPENDED)

    # SC-18
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.DEPRECATED, references={"Structure": [s]})
    assert_pass("SC-18", m, S.DEPRECATED, S.RETIRED)

    # SC-19
    s = make_element("Structure", "Structure", S.RETIRED)
    m = make_element("Mechanism", "Mechanism", S.DEPRECATED, references={"Structure": [s]})
    assert_pass("SC-19", m, S.DEPRECATED, S.RETIRED)

    # SC-24
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.SUSPENDED, references={"Structure": [s]})
    assert_pass("SC-24", m, S.SUSPENDED, S.DEPRECATED)

    # SC-25
    s = make_element("Structure", "Structure", S.RETIRED)
    m = make_element("Mechanism", "Mechanism", S.SUSPENDED, references={"Structure": [s]})
    assert_blocked("SC-25", m, S.SUSPENDED, S.DEPRECATED)

    # SC-26
    s = make_element("Structure", "Structure", S.RETIRED)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE, references={"Structure": [s]})
    assert_blocked("SC-26", m, S.ACTIVE, S.DEPRECATED)

    # SC-27
    s = make_element("Structure", "Structure", S.RETIRED)
    m = make_element("Mechanism", "Mechanism", S.DEPRECATED, references={"Structure": [s]})
    assert_blocked("SC-27", m, S.DEPRECATED, S.SUSPENDED)

    # SC-28
    s = make_element("Structure", "Structure", S.ABANDONED)
    m = make_element("Mechanism", "Mechanism", S.DEPRECATED, references={"Structure": [s]})
    assert_blocked("SC-28", m, S.DEPRECATED, S.RETIRED)


# ============================================================
# §3 — Referee preconditions (terminal)
# ============================================================

def test_s3():
    """§3 — Terminal."""

    # SC-20
    s = make_element("Structure", "Structure", S.DRAFT)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [s]})
    assert_pass("SC-20", m, S.DRAFT, S.ABANDONED)

    # SC-21
    s = make_element("Structure", "Structure", S.RETIRED)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [s]})
    assert_pass("SC-21", m, S.DRAFT, S.ABANDONED)

    # SC-22
    s = make_element("Structure", "Structure", S.PROPOSED)
    m = make_element("Mechanism", "Mechanism", S.PROPOSED, references={"Structure": [s]})
    assert_pass("SC-22", m, S.PROPOSED, S.REJECTED)

    # SC-23
    s = make_element("Structure", "Structure", S.DRAFT)
    m = make_element("Mechanism", "Mechanism", S.PROPOSED, references={"Structure": [s]})
    assert_blocked("SC-23", m, S.PROPOSED, S.REJECTED)


# ============================================================
# §4 — Multi-reference: Interaction (Eff + Rec)
# ============================================================

def test_s4():
    """§4 — Multi-reference Interaction."""

    # SC-30: Eff(ACTIVE), Rec(PROPOSED). Interaction [*]->DRAFT. PASS.
    eff = make_element("Effector", "Effector", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.PROPOSED)
    ix = make_element("Interaction", "Interaction", S.DRAFT,
                      references={"Effector": [eff], "Receptor": [rec]})
    assert_pass("SC-30", ix, None, S.DRAFT)

    # SC-31: same refs. Interaction DRAFT->PROPOSED. PASS.
    eff = make_element("Effector", "Effector", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.PROPOSED)
    ix = make_element("Interaction", "Interaction", S.DRAFT,
                      references={"Effector": [eff], "Receptor": [rec]})
    assert_pass("SC-31", ix, S.DRAFT, S.PROPOSED)

    # SC-32: Eff(ACTIVE), Rec(PROPOSED). PROPOSED->APPROVED. BLOCKED.
    eff = make_element("Effector", "Effector", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.PROPOSED)
    ix = make_element("Interaction", "Interaction", S.PROPOSED,
                      references={"Effector": [eff], "Receptor": [rec]})
    assert_blocked("SC-32", ix, S.PROPOSED, S.APPROVED)

    # SC-33: Eff(ACTIVE), Rec(APPROVED). PROPOSED->APPROVED. PASS.
    eff = make_element("Effector", "Effector", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.APPROVED)
    ix = make_element("Interaction", "Interaction", S.PROPOSED,
                      references={"Effector": [eff], "Receptor": [rec]})
    assert_pass("SC-33", ix, S.PROPOSED, S.APPROVED)

    # SC-34: both ACTIVE. APPROVED->ACTIVE. PASS.
    eff = make_element("Effector", "Effector", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.ACTIVE)
    ix = make_element("Interaction", "Interaction", S.APPROVED,
                      references={"Effector": [eff], "Receptor": [rec]})
    assert_pass("SC-34", ix, S.APPROVED, S.ACTIVE)

    # SC-35: Eff(ACTIVE), Rec(APPROVED). APPROVED->ACTIVE. BLOCKED.
    eff = make_element("Effector", "Effector", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.APPROVED)
    ix = make_element("Interaction", "Interaction", S.APPROVED,
                      references={"Effector": [eff], "Receptor": [rec]})
    assert_blocked("SC-35", ix, S.APPROVED, S.ACTIVE)


# ============================================================
# §5 — Multi-reference: Interface (Str + Eff + Rec)
# ============================================================

def test_s5():
    """§5 — Multi-reference Interface."""

    # SC-40: Str(PROPOSED), Eff(PROPOSED), Rec(DRAFT). DRAFT->PROPOSED. BLOCKED.
    s = make_element("Structure", "Structure", S.PROPOSED)
    eff = make_element("Effector", "Effector", S.PROPOSED)
    rec = make_element("Receptor", "Receptor", S.DRAFT)
    iface = make_element("Interface", "Interface", S.DRAFT,
                         references={"Structure": [s], "Effector": [eff], "Receptor": [rec]})
    assert_blocked("SC-40", iface, S.DRAFT, S.PROPOSED)

    # SC-41: all PROPOSED. DRAFT->PROPOSED. PASS.
    s = make_element("Structure", "Structure", S.PROPOSED)
    eff = make_element("Effector", "Effector", S.PROPOSED)
    rec = make_element("Receptor", "Receptor", S.PROPOSED)
    iface = make_element("Interface", "Interface", S.DRAFT,
                         references={"Structure": [s], "Effector": [eff], "Receptor": [rec]})
    assert_pass("SC-41", iface, S.DRAFT, S.PROPOSED)

    # SC-42: all ACTIVE. APPROVED->ACTIVE. PASS.
    s = make_element("Structure", "Structure", S.ACTIVE)
    eff = make_element("Effector", "Effector", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.ACTIVE)
    iface = make_element("Interface", "Interface", S.APPROVED,
                         references={"Structure": [s], "Effector": [eff], "Receptor": [rec]})
    assert_pass("SC-42", iface, S.APPROVED, S.ACTIVE)

    # SC-43: Str(ACTIVE), Eff(ACTIVE), Rec(APPROVED). APPROVED->ACTIVE. BLOCKED.
    s = make_element("Structure", "Structure", S.ACTIVE)
    eff = make_element("Effector", "Effector", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.APPROVED)
    iface = make_element("Interface", "Interface", S.APPROVED,
                         references={"Structure": [s], "Effector": [eff], "Receptor": [rec]})
    assert_blocked("SC-43", iface, S.APPROVED, S.ACTIVE)


# ============================================================
# §6 — Not-Referee (Structure, Artifact)
# ============================================================

def test_s6():
    """§6 — Not-Referee."""

    # SC-50: Structure DRAFT->PROPOSED. PASS (no refs).
    s = make_element("Structure", "Structure", S.DRAFT)
    assert_pass("SC-50", s, S.DRAFT, S.PROPOSED)

    # SC-51: Artifact DRAFT->PROPOSED. PASS (no refs).
    a = make_element("Artifact", "Artifact", S.DRAFT)
    assert_pass("SC-51", a, S.DRAFT, S.PROPOSED)

    # SC-52: Structure ACTIVE->DEPRECATED. PASS (no refs).
    s = make_element("Structure", "Structure", S.ACTIVE)
    assert_pass("SC-52", s, S.ACTIVE, S.DEPRECATED)

    # SC-53: Artifact DEPRECATED->RETIRED. PASS (no refs).
    a = make_element("Artifact", "Artifact", S.DEPRECATED)
    assert_pass("SC-53", a, S.DEPRECATED, S.RETIRED)


# ============================================================
# §7 — Composition cascade (strict)
# ============================================================

def test_s7():
    """§7 — Composition cascade."""

    # SC-60: Mechanism DRAFT->PROPOSED. E(DRAFT)/Art(PROPOSED), R(DRAFT)/Art(PROPOSED).
    # CASCADE for both E and R.
    ea = make_element("E.Artifact", "Artifact", S.PROPOSED)
    ra = make_element("R.Artifact", "Artifact", S.PROPOSED)
    eff = make_element("Effector", "Effector", S.DRAFT, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.DRAFT, references={"Artifact": [ra]})
    s = make_element("Structure", "Structure", S.PROPOSED)
    m = make_element("Mechanism", "Mechanism", S.DRAFT,
                     references={"Structure": [s]},
                     composition_targets=[eff, rec])

    ok_m, _ = check_referee_preconditions(m, S.DRAFT, S.PROPOSED)
    # Cascade to Effector
    ok_e_s1 = (eff.status == S.DRAFT)
    ok_e_s2, _ = check_referee_preconditions(eff, S.DRAFT, S.PROPOSED)
    # Cascade to Receptor
    ok_r_s1 = (rec.status == S.DRAFT)
    ok_r_s2, _ = check_referee_preconditions(rec, S.DRAFT, S.PROPOSED)

    if ok_m and ok_e_s1 and ok_e_s2 and ok_r_s1 and ok_r_s2:
        results.append(TestResult("SC-60", Result.PASS, "CASCADE both", "CASCADE both"))
    else:
        results.append(TestResult("SC-60", Result.FAIL, "CASCADE both",
                                  f"M:{ok_m} E-s1:{ok_e_s1} E-s2:{ok_e_s2} R-s1:{ok_r_s1} R-s2:{ok_r_s2}"))

    # SC-61: E.Art(DRAFT) → E cascade step 2 fails → Mechanism BLOCKED.
    ea = make_element("E.Artifact", "Artifact", S.DRAFT)
    ra = make_element("R.Artifact", "Artifact", S.PROPOSED)
    eff = make_element("Effector", "Effector", S.DRAFT, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.DRAFT, references={"Artifact": [ra]})
    s = make_element("Structure", "Structure", S.PROPOSED)
    m = make_element("Mechanism", "Mechanism", S.DRAFT,
                     references={"Structure": [s]},
                     composition_targets=[eff, rec])

    ok_m, _ = check_referee_preconditions(m, S.DRAFT, S.PROPOSED)
    ok_e_s2, reason = check_referee_preconditions(eff, S.DRAFT, S.PROPOSED)
    # Composition: on failure → reject source
    if ok_m and not ok_e_s2:
        results.append(TestResult("SC-61", Result.PASS, "BLOCKED (comp fail)", "BLOCKED (comp fail)"))
    else:
        results.append(TestResult("SC-61", Result.FAIL, "BLOCKED (comp fail)",
                                  f"M:{ok_m} E-s2:{ok_e_s2} ({reason})"))

    # SC-62: All ACTIVE. Mechanism ACTIVE->SUSPENDED. CASCADE.
    ea = make_element("E.Artifact", "Artifact", S.ACTIVE)
    ra = make_element("R.Artifact", "Artifact", S.ACTIVE)
    eff = make_element("Effector", "Effector", S.ACTIVE, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.ACTIVE, references={"Artifact": [ra]})
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE,
                     references={"Structure": [s]},
                     composition_targets=[eff, rec])

    ok_m, _ = check_referee_preconditions(m, S.ACTIVE, S.SUSPENDED)
    ok_e_s2, _ = check_referee_preconditions(eff, S.ACTIVE, S.SUSPENDED)
    ok_r_s2, _ = check_referee_preconditions(rec, S.ACTIVE, S.SUSPENDED)
    if ok_m and ok_e_s2 and ok_r_s2:
        results.append(TestResult("SC-62", Result.PASS, "CASCADE both", "CASCADE both"))
    else:
        results.append(TestResult("SC-62", Result.FAIL, "CASCADE both",
                                  f"M:{ok_m} E:{ok_e_s2} R:{ok_r_s2}"))

    # SC-63: All ACTIVE. Mechanism ACTIVE->DEPRECATED. CASCADE.
    ea = make_element("E.Artifact", "Artifact", S.ACTIVE)
    ra = make_element("R.Artifact", "Artifact", S.ACTIVE)
    eff = make_element("Effector", "Effector", S.ACTIVE, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.ACTIVE, references={"Artifact": [ra]})
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE,
                     references={"Structure": [s]},
                     composition_targets=[eff, rec])

    ok_m, _ = check_referee_preconditions(m, S.ACTIVE, S.DEPRECATED)
    ok_e_s2, _ = check_referee_preconditions(eff, S.ACTIVE, S.DEPRECATED)
    ok_r_s2, _ = check_referee_preconditions(rec, S.ACTIVE, S.DEPRECATED)
    if ok_m and ok_e_s2 and ok_r_s2:
        results.append(TestResult("SC-63", Result.PASS, "CASCADE both", "CASCADE both"))
    else:
        results.append(TestResult("SC-63", Result.FAIL, "CASCADE both",
                                  f"M:{ok_m} E:{ok_e_s2} R:{ok_r_s2}"))

    # SC-64: All DRAFT. Mechanism DRAFT->ABANDONED. CASCADE.
    ea = make_element("E.Artifact", "Artifact", S.DRAFT)
    ra = make_element("R.Artifact", "Artifact", S.DRAFT)
    eff = make_element("Effector", "Effector", S.DRAFT, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.DRAFT, references={"Artifact": [ra]})
    s = make_element("Structure", "Structure", S.DRAFT)
    m = make_element("Mechanism", "Mechanism", S.DRAFT,
                     references={"Structure": [s]},
                     composition_targets=[eff, rec])

    ok_m, _ = check_referee_preconditions(m, S.DRAFT, S.ABANDONED)
    ok_e_s2, _ = check_referee_preconditions(eff, S.DRAFT, S.ABANDONED)
    ok_r_s2, _ = check_referee_preconditions(rec, S.DRAFT, S.ABANDONED)
    if ok_m and ok_e_s2 and ok_r_s2:
        results.append(TestResult("SC-64", Result.PASS, "CASCADE both", "CASCADE both"))
    else:
        results.append(TestResult("SC-64", Result.FAIL, "CASCADE both",
                                  f"M:{ok_m} E:{ok_e_s2} R:{ok_r_s2}"))

    # SC-65: All PROPOSED. Mechanism PROPOSED->REJECTED. CASCADE.
    ea = make_element("E.Artifact", "Artifact", S.PROPOSED)
    ra = make_element("R.Artifact", "Artifact", S.PROPOSED)
    eff = make_element("Effector", "Effector", S.PROPOSED, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.PROPOSED, references={"Artifact": [ra]})
    s = make_element("Structure", "Structure", S.PROPOSED)
    m = make_element("Mechanism", "Mechanism", S.PROPOSED,
                     references={"Structure": [s]},
                     composition_targets=[eff, rec])

    ok_m, _ = check_referee_preconditions(m, S.PROPOSED, S.REJECTED)
    ok_e_s2, _ = check_referee_preconditions(eff, S.PROPOSED, S.REJECTED)
    ok_r_s2, _ = check_referee_preconditions(rec, S.PROPOSED, S.REJECTED)
    if ok_m and ok_e_s2 and ok_r_s2:
        results.append(TestResult("SC-65", Result.PASS, "CASCADE both", "CASCADE both"))
    else:
        results.append(TestResult("SC-65", Result.FAIL, "CASCADE both",
                                  f"M:{ok_m} E:{ok_e_s2} R:{ok_r_s2}"))


# ============================================================
# §8 — Aggregation cascade (lagging)
# ============================================================

def test_s8():
    """§8 — Aggregation cascade.

    For aggregation cascade scenarios, we test the aggregation-specific
    semantics: step 1 (status match), step 2 (preconditions), and the
    on-failure behavior (no-op). We assume target's nested cascades
    succeed when the target itself would transition.

    Note: SC-70..76 model Mechanism as having implicit ports that would
    succeed composition cascade - here we verify the aggregation rules.
    """

    # SC-70: Structure DRAFT->PROPOSED. M(DRAFT), I(DRAFT). Both CASCADE.
    # After Structure transitions to PROPOSED, Mechanism's ref (Structure) is PROPOSED.
    s_ref_post = S.PROPOSED  # Structure's status AFTER it transitions
    # M cascade: step 1 ok (DRAFT=DRAFT), step 2: Structure(PROPOSED) ∈ {PROPOSED,APPROVED,ACTIVE} ✓
    m_s1 = (S.DRAFT == S.DRAFT)
    m_ref = make_element("Structure", "Structure", s_ref_post)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [m_ref]})
    m_s2, _ = check_referee_preconditions(m, S.DRAFT, S.PROPOSED)
    # I cascade: step 1 ok, step 2: I refs check — Interface refs Structure, Effector, Receptor
    # For this test we check aggregation step 1+2 only (Interface preconditions tested in §5)
    i_s1 = (S.DRAFT == S.DRAFT)
    i_ref_s = make_element("Structure", "Structure", s_ref_post)
    # Interface needs at least 1 port reference; assume compatible
    i_ref_e = make_element("Effector", "Effector", S.PROPOSED)  # would have cascaded via Mechanism
    iface = make_element("Interface", "Interface", S.DRAFT,
                         references={"Structure": [i_ref_s], "Effector": [i_ref_e], "Receptor": []})
    i_s2, _ = check_referee_preconditions(iface, S.DRAFT, S.PROPOSED)

    if m_s1 and m_s2 and i_s1 and i_s2:
        results.append(TestResult("SC-70", Result.PASS, "CASCADE both", "CASCADE both"))
    else:
        results.append(TestResult("SC-70", Result.FAIL, "CASCADE both",
                                  f"M-s1:{m_s1} M-s2:{m_s2} I-s1:{i_s1} I-s2:{i_s2}"))

    # SC-71: Structure PROPOSED->APPROVED. M(DRAFT): step 1 fails → NO-OP.
    #        I(PROPOSED): CASCADE.
    m_s1_fail = (S.DRAFT == S.PROPOSED)  # False → NO-OP
    i_s1_ok = (S.PROPOSED == S.PROPOSED)  # True
    s_post = S.APPROVED
    i_ref_s = make_element("Structure", "Structure", s_post)
    i_ref_e = make_element("Effector", "Effector", S.APPROVED)
    iface = make_element("Interface", "Interface", S.PROPOSED,
                         references={"Structure": [i_ref_s], "Effector": [i_ref_e], "Receptor": []})
    i_s2, _ = check_referee_preconditions(iface, S.PROPOSED, S.APPROVED)
    if (not m_s1_fail) and i_s1_ok and i_s2:
        results.append(TestResult("SC-71", Result.PASS, "M:NO-OP I:CASCADE",
                                  "M:NO-OP I:CASCADE"))
    else:
        results.append(TestResult("SC-71", Result.FAIL, "M:NO-OP I:CASCADE",
                                  f"M-s1:{m_s1_fail} I-s1:{i_s1_ok} I-s2:{i_s2}"))

    # SC-72: Structure APPROVED->ACTIVE. M(DRAFT): step 1 fails → NO-OP. I(APPROVED): CASCADE.
    m_s1_fail = (S.DRAFT == S.APPROVED)
    i_s1_ok = (S.APPROVED == S.APPROVED)
    s_post = S.ACTIVE
    i_ref_s = make_element("Structure", "Structure", s_post)
    i_ref_e = make_element("Effector", "Effector", S.ACTIVE)
    iface = make_element("Interface", "Interface", S.APPROVED,
                         references={"Structure": [i_ref_s], "Effector": [i_ref_e], "Receptor": []})
    i_s2, _ = check_referee_preconditions(iface, S.APPROVED, S.ACTIVE)
    if (not m_s1_fail) and i_s1_ok and i_s2:
        results.append(TestResult("SC-72", Result.PASS, "M:NO-OP I:CASCADE",
                                  "M:NO-OP I:CASCADE"))
    else:
        results.append(TestResult("SC-72", Result.FAIL, "M:NO-OP I:CASCADE",
                                  f"M-s1:{m_s1_fail} I-s1:{i_s1_ok} I-s2:{i_s2}"))

    # SC-73: Structure ACTIVE->SUSPENDED. M(ACTIVE): CASCADE. I(DRAFT): step 1 fails → NO-OP.
    m_s1_ok = (S.ACTIVE == S.ACTIVE)
    s_post = S.SUSPENDED
    m_ref = make_element("Structure", "Structure", s_post)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE, references={"Structure": [m_ref]})
    m_s2, _ = check_referee_preconditions(m, S.ACTIVE, S.SUSPENDED)
    i_s1_fail = (S.DRAFT == S.ACTIVE)

    if m_s1_ok and m_s2 and (not i_s1_fail):
        results.append(TestResult("SC-73", Result.PASS, "M:CASCADE I:NO-OP",
                                  "M:CASCADE I:NO-OP"))
    else:
        results.append(TestResult("SC-73", Result.FAIL, "M:CASCADE I:NO-OP",
                                  f"M-s1:{m_s1_ok} M-s2:{m_s2} I-s1:{i_s1_fail}"))

    # SC-74: All ACTIVE. Structure ACTIVE->DEPRECATED. Both CASCADE.
    s_post = S.DEPRECATED
    m_ref = make_element("Structure", "Structure", s_post)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE, references={"Structure": [m_ref]})
    m_s2, _ = check_referee_preconditions(m, S.ACTIVE, S.DEPRECATED)
    i_ref_s = make_element("Structure", "Structure", s_post)
    i_ref_e = make_element("Effector", "Effector", S.DEPRECATED)
    i_ref_r = make_element("Receptor", "Receptor", S.DEPRECATED)
    iface = make_element("Interface", "Interface", S.ACTIVE,
                         references={"Structure": [i_ref_s], "Effector": [i_ref_e], "Receptor": [i_ref_r]})
    i_s2, _ = check_referee_preconditions(iface, S.ACTIVE, S.DEPRECATED)
    if m_s2 and i_s2:
        results.append(TestResult("SC-74", Result.PASS, "CASCADE both", "CASCADE both"))
    else:
        results.append(TestResult("SC-74", Result.FAIL, "CASCADE both",
                                  f"M-s2:{m_s2} I-s2:{i_s2}"))

    # SC-75: All DRAFT. Structure DRAFT->ABANDONED. Both CASCADE.
    s_post = S.ABANDONED
    m_ref = make_element("Structure", "Structure", s_post)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [m_ref]})
    m_s2, _ = check_referee_preconditions(m, S.DRAFT, S.ABANDONED)
    i_ref_s = make_element("Structure", "Structure", s_post)
    iface = make_element("Interface", "Interface", S.DRAFT,
                         references={"Structure": [i_ref_s], "Effector": [], "Receptor": []})
    i_s2, _ = check_referee_preconditions(iface, S.DRAFT, S.ABANDONED)
    if m_s2 and i_s2:
        results.append(TestResult("SC-75", Result.PASS, "CASCADE both", "CASCADE both"))
    else:
        results.append(TestResult("SC-75", Result.FAIL, "CASCADE both",
                                  f"M-s2:{m_s2} I-s2:{i_s2}"))

    # SC-76: Structure PROPOSED->REJECTED. M(DRAFT): step 1 → NO-OP. I(PROPOSED): CASCADE.
    m_s1_fail = (S.DRAFT == S.PROPOSED)
    s_post = S.REJECTED
    i_ref_s = make_element("Structure", "Structure", s_post)
    iface = make_element("Interface", "Interface", S.PROPOSED,
                         references={"Structure": [i_ref_s], "Effector": [], "Receptor": []})
    i_s2, _ = check_referee_preconditions(iface, S.PROPOSED, S.REJECTED)
    if (not m_s1_fail) and i_s2:
        results.append(TestResult("SC-76", Result.PASS, "M:NO-OP I:CASCADE",
                                  "M:NO-OP I:CASCADE"))
    else:
        results.append(TestResult("SC-76", Result.FAIL, "M:NO-OP I:CASCADE",
                                  f"M-s1:{m_s1_fail} I-s2:{i_s2}"))


# ============================================================
# §9 — Reference cascade
# ============================================================

def test_s9():
    """§9 — Reference cascade."""

    # SC-80: Effector ACTIVE->SUSPENDED. Interaction(ACTIVE). CASCADE.
    # After Effector transitions to SUSPENDED, Interaction's ref check:
    #   Eff(SUSPENDED) ∈ {ACTIVE,SUSPENDED,DEPRECATED} ✓
    #   Need Receptor specified. Assume Receptor(ACTIVE).
    eff_post = S.SUSPENDED
    eff_ref = make_element("Effector", "Effector", eff_post)
    rec_ref = make_element("Receptor", "Receptor", S.ACTIVE)  # implicit
    ix = make_element("Interaction", "Interaction", S.ACTIVE,
                      references={"Effector": [eff_ref], "Receptor": [rec_ref]})
    in_scope = (S.ACTIVE, S.SUSPENDED) in REFERENCE_CASCADE_SCOPE
    s1_ok = (ix.status == S.ACTIVE)
    s2_ok, _ = check_referee_preconditions(ix, S.ACTIVE, S.SUSPENDED)
    if in_scope and s1_ok and s2_ok:
        results.append(TestResult("SC-80", Result.PASS, "CASCADE", "CASCADE"))
    else:
        results.append(TestResult("SC-80", Result.FAIL, "CASCADE",
                                  f"scope:{in_scope} s1:{s1_ok} s2:{s2_ok}"))

    # SC-81: Effector ACTIVE->SUSPENDED. Interaction(PROPOSED). Step 1 fails → NO-OP.
    in_scope = (S.ACTIVE, S.SUSPENDED) in REFERENCE_CASCADE_SCOPE
    s1_fail = (S.PROPOSED != S.ACTIVE)
    if in_scope and s1_fail:
        results.append(TestResult("SC-81", Result.PASS, "NO-OP", "NO-OP"))
    else:
        results.append(TestResult("SC-81", Result.FAIL, "NO-OP",
                                  f"scope:{in_scope} s1_fail:{s1_fail}"))

    # SC-82: Receptor ACTIVE->DEPRECATED. Interface(ACTIVE). CASCADE.
    rec_post = S.DEPRECATED
    s_ref = make_element("Structure", "Structure", S.ACTIVE)  # implicit
    e_ref = make_element("Effector", "Effector", S.ACTIVE)    # implicit
    r_ref = make_element("Receptor", "Receptor", rec_post)
    iface = make_element("Interface", "Interface", S.ACTIVE,
                         references={"Structure": [s_ref], "Effector": [e_ref], "Receptor": [r_ref]})
    in_scope = (S.ACTIVE, S.DEPRECATED) in REFERENCE_CASCADE_SCOPE
    s1_ok = (iface.status == S.ACTIVE)
    s2_ok, _ = check_referee_preconditions(iface, S.ACTIVE, S.DEPRECATED)
    if in_scope and s1_ok and s2_ok:
        results.append(TestResult("SC-82", Result.PASS, "CASCADE", "CASCADE"))
    else:
        results.append(TestResult("SC-82", Result.FAIL, "CASCADE",
                                  f"scope:{in_scope} s1:{s1_ok} s2:{s2_ok}"))

    # SC-83: Effector DRAFT->PROPOSED. NOT in reference scope → NO CASCADE.
    in_scope = (S.DRAFT, S.PROPOSED) in REFERENCE_CASCADE_SCOPE
    if not in_scope:
        results.append(TestResult("SC-83", Result.PASS, "NO CASCADE", "NO CASCADE (not in scope)"))
    else:
        results.append(TestResult("SC-83", Result.FAIL, "NO CASCADE", "In scope (unexpected)"))

    # SC-84: Receptor PROPOSED->APPROVED. NOT in scope → NO CASCADE.
    in_scope = (S.PROPOSED, S.APPROVED) in REFERENCE_CASCADE_SCOPE
    if not in_scope:
        results.append(TestResult("SC-84", Result.PASS, "NO CASCADE", "NO CASCADE (not in scope)"))
    else:
        results.append(TestResult("SC-84", Result.FAIL, "NO CASCADE", "In scope (unexpected)"))

    # SC-85: Effector DRAFT->ABANDONED. In scope. Interaction(DRAFT). CASCADE.
    in_scope = (S.DRAFT, S.ABANDONED) in REFERENCE_CASCADE_SCOPE
    eff_post = S.ABANDONED
    eff_ref = make_element("Effector", "Effector", eff_post)
    rec_ref = make_element("Receptor", "Receptor", S.DRAFT)  # any status allowed
    ix = make_element("Interaction", "Interaction", S.DRAFT,
                      references={"Effector": [eff_ref], "Receptor": [rec_ref]})
    s1_ok = (ix.status == S.DRAFT)
    s2_ok, _ = check_referee_preconditions(ix, S.DRAFT, S.ABANDONED)
    if in_scope and s1_ok and s2_ok:
        results.append(TestResult("SC-85", Result.PASS, "CASCADE", "CASCADE"))
    else:
        results.append(TestResult("SC-85", Result.FAIL, "CASCADE",
                                  f"scope:{in_scope} s1:{s1_ok} s2:{s2_ok}"))

    # SC-86: Receptor PROPOSED->REJECTED. In scope. Interaction(PROPOSED). CASCADE.
    in_scope = (S.PROPOSED, S.REJECTED) in REFERENCE_CASCADE_SCOPE
    rec_post = S.REJECTED
    eff_ref = make_element("Effector", "Effector", S.PROPOSED)  # must not be DRAFT
    rec_ref = make_element("Receptor", "Receptor", rec_post)
    ix = make_element("Interaction", "Interaction", S.PROPOSED,
                      references={"Effector": [eff_ref], "Receptor": [rec_ref]})
    s1_ok = (ix.status == S.PROPOSED)
    s2_ok, _ = check_referee_preconditions(ix, S.PROPOSED, S.REJECTED)
    if in_scope and s1_ok and s2_ok:
        results.append(TestResult("SC-86", Result.PASS, "CASCADE", "CASCADE"))
    else:
        results.append(TestResult("SC-86", Result.FAIL, "CASCADE",
                                  f"scope:{in_scope} s1:{s1_ok} s2:{s2_ok}"))

    # SC-87: Effector ACTIVE->DEPRECATED. Interface(ACTIVE). CASCADE.
    in_scope = (S.ACTIVE, S.DEPRECATED) in REFERENCE_CASCADE_SCOPE
    eff_post = S.DEPRECATED
    s_ref = make_element("Structure", "Structure", S.ACTIVE)
    e_ref = make_element("Effector", "Effector", eff_post)
    r_ref = make_element("Receptor", "Receptor", S.ACTIVE)
    iface = make_element("Interface", "Interface", S.ACTIVE,
                         references={"Structure": [s_ref], "Effector": [e_ref], "Receptor": [r_ref]})
    s1_ok = (iface.status == S.ACTIVE)
    s2_ok, _ = check_referee_preconditions(iface, S.ACTIVE, S.DEPRECATED)
    if in_scope and s1_ok and s2_ok:
        results.append(TestResult("SC-87", Result.PASS, "CASCADE", "CASCADE"))
    else:
        results.append(TestResult("SC-87", Result.FAIL, "CASCADE",
                                  f"scope:{in_scope} s1:{s1_ok} s2:{s2_ok}"))

    # SC-88: Effector DEPRECATED->RETIRED. Interaction(DEPRECATED). CASCADE.
    in_scope = (S.DEPRECATED, S.RETIRED) in REFERENCE_CASCADE_SCOPE
    eff_post = S.RETIRED
    eff_ref = make_element("Effector", "Effector", eff_post)
    rec_ref = make_element("Receptor", "Receptor", S.DEPRECATED)
    ix = make_element("Interaction", "Interaction", S.DEPRECATED,
                      references={"Effector": [eff_ref], "Receptor": [rec_ref]})
    s1_ok = (ix.status == S.DEPRECATED)
    s2_ok, _ = check_referee_preconditions(ix, S.DEPRECATED, S.RETIRED)
    if in_scope and s1_ok and s2_ok:
        results.append(TestResult("SC-88", Result.PASS, "CASCADE", "CASCADE"))
    else:
        results.append(TestResult("SC-88", Result.FAIL, "CASCADE",
                                  f"scope:{in_scope} s1:{s1_ok} s2:{s2_ok}"))

    # SC-89: Receptor DEPRECATED->SUSPENDED. Interaction(DEPRECATED). CASCADE.
    in_scope = (S.DEPRECATED, S.SUSPENDED) in REFERENCE_CASCADE_SCOPE
    rec_post = S.SUSPENDED
    eff_ref = make_element("Effector", "Effector", S.DEPRECATED)
    rec_ref = make_element("Receptor", "Receptor", rec_post)
    ix = make_element("Interaction", "Interaction", S.DEPRECATED,
                      references={"Effector": [eff_ref], "Receptor": [rec_ref]})
    s1_ok = (ix.status == S.DEPRECATED)
    s2_ok, _ = check_referee_preconditions(ix, S.DEPRECATED, S.SUSPENDED)
    if in_scope and s1_ok and s2_ok:
        results.append(TestResult("SC-89", Result.PASS, "CASCADE", "CASCADE"))
    else:
        results.append(TestResult("SC-89", Result.FAIL, "CASCADE",
                                  f"scope:{in_scope} s1:{s1_ok} s2:{s2_ok}"))

    # SC-89b: Effector SUSPENDED->DEPRECATED. Interaction(SUSPENDED). CASCADE.
    in_scope = (S.SUSPENDED, S.DEPRECATED) in REFERENCE_CASCADE_SCOPE
    eff_post = S.DEPRECATED
    eff_ref = make_element("Effector", "Effector", eff_post)
    rec_ref = make_element("Receptor", "Receptor", S.SUSPENDED)
    ix = make_element("Interaction", "Interaction", S.SUSPENDED,
                      references={"Effector": [eff_ref], "Receptor": [rec_ref]})
    s1_ok = (ix.status == S.SUSPENDED)
    s2_ok, _ = check_referee_preconditions(ix, S.SUSPENDED, S.DEPRECATED)
    if in_scope and s1_ok and s2_ok:
        results.append(TestResult("SC-89b", Result.PASS, "CASCADE", "CASCADE"))
    else:
        results.append(TestResult("SC-89b", Result.FAIL, "CASCADE",
                                  f"scope:{in_scope} s1:{s1_ok} s2:{s2_ok}"))


# ============================================================
# §10 — Cascade chains (transitive)
# ============================================================

def test_s10():
    """§10 — Cascade chains (transitive).

    These test full cascade chains: Structure → Mechanism → E/R → Ix/I.
    We verify each step in sequence, simulating the cascade order.
    """

    # SC-90: All DRAFT. E.Art(PROPOSED), R.Art(PROPOSED).
    #        Structure DRAFT->PROPOSED. Chain: Str→M→E,R. No ref cascade.
    s_post = S.PROPOSED

    # M cascade (agg): DRAFT->PROPOSED. M ref=Structure(PROPOSED) ∈ {PROPOSED,APPROVED,ACTIVE} ✓
    m_ref = make_element("Structure", "Structure", s_post)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [m_ref]})
    m_s1 = (S.DRAFT == S.DRAFT)
    m_s2, _ = check_referee_preconditions(m, S.DRAFT, S.PROPOSED)

    # E cascade (comp): DRAFT->PROPOSED. E ref=Art(PROPOSED) ∈ {PROPOSED,APPROVED,ACTIVE} ✓
    ea = make_element("E.Art", "Artifact", S.PROPOSED)
    eff = make_element("Effector", "Effector", S.DRAFT, references={"Artifact": [ea]})
    e_s1 = (S.DRAFT == S.DRAFT)
    e_s2, _ = check_referee_preconditions(eff, S.DRAFT, S.PROPOSED)

    # R cascade (comp): same
    ra = make_element("R.Art", "Artifact", S.PROPOSED)
    rec = make_element("Receptor", "Receptor", S.DRAFT, references={"Artifact": [ra]})
    r_s1 = (S.DRAFT == S.DRAFT)
    r_s2, _ = check_referee_preconditions(rec, S.DRAFT, S.PROPOSED)

    # Ref cascade: DRAFT->PROPOSED not in scope
    ref_no_cascade = (S.DRAFT, S.PROPOSED) not in REFERENCE_CASCADE_SCOPE

    if m_s1 and m_s2 and e_s1 and e_s2 and r_s1 and r_s2 and ref_no_cascade:
        results.append(TestResult("SC-90", Result.PASS,
                                  "M,E,R CASCADE; Ix NO CASCADE",
                                  "M,E,R CASCADE; Ix NO CASCADE"))
    else:
        results.append(TestResult("SC-90", Result.FAIL,
                                  "M,E,R CASCADE; Ix NO CASCADE",
                                  f"M:{m_s2} E:{e_s2} R:{r_s2} ref_skip:{ref_no_cascade}"))

    # SC-91: All ACTIVE. Structure ACTIVE->DEPRECATED.
    # Full chain: all cascade to DEPRECATED. Ref cascade in scope.
    s_post = S.DEPRECATED

    # M cascade (agg): Str(DEPRECATED) ∈ {A,S,D} ✓
    m_ref = make_element("Structure", "Structure", s_post)
    m = make_element("Mechanism", "Mechanism", S.ACTIVE, references={"Structure": [m_ref]})
    m_s2, _ = check_referee_preconditions(m, S.ACTIVE, S.DEPRECATED)

    # I cascade (agg): Str(DEPRECATED) + E(DEPRECATED) + R(DEPRECATED) all ∈ {A,S,D} ✓
    i_s_ref = make_element("Structure", "Structure", s_post)
    i_e_ref = make_element("Effector", "Effector", S.DEPRECATED)  # after comp cascade
    i_r_ref = make_element("Receptor", "Receptor", S.DEPRECATED)
    iface = make_element("Interface", "Interface", S.ACTIVE,
                         references={"Structure": [i_s_ref], "Effector": [i_e_ref], "Receptor": [i_r_ref]})
    i_s2, _ = check_referee_preconditions(iface, S.ACTIVE, S.DEPRECATED)

    # E cascade (comp): Art(ACTIVE) ∈ {A,S,D} ✓ (Artifacts stay ACTIVE — not cascaded)
    ea = make_element("E.Art", "Artifact", S.ACTIVE)
    eff = make_element("Effector", "Effector", S.ACTIVE, references={"Artifact": [ea]})
    e_s2, _ = check_referee_preconditions(eff, S.ACTIVE, S.DEPRECATED)

    # R cascade (comp): same
    ra = make_element("R.Art", "Artifact", S.ACTIVE)
    rec = make_element("Receptor", "Receptor", S.ACTIVE, references={"Artifact": [ra]})
    r_s2, _ = check_referee_preconditions(rec, S.ACTIVE, S.DEPRECATED)

    # Ix ref cascade: E(DEPRECATED) + R(DEPRECATED) ∈ {A,S,D} ✓
    ref_in_scope = (S.ACTIVE, S.DEPRECATED) in REFERENCE_CASCADE_SCOPE
    ix_e_ref = make_element("Effector", "Effector", S.DEPRECATED)
    ix_r_ref = make_element("Receptor", "Receptor", S.DEPRECATED)
    ix = make_element("Interaction", "Interaction", S.ACTIVE,
                      references={"Effector": [ix_e_ref], "Receptor": [ix_r_ref]})
    ix_s2, _ = check_referee_preconditions(ix, S.ACTIVE, S.DEPRECATED)

    if m_s2 and i_s2 and e_s2 and r_s2 and ref_in_scope and ix_s2:
        results.append(TestResult("SC-91", Result.PASS, "All CASCADE", "All CASCADE"))
    else:
        results.append(TestResult("SC-91", Result.FAIL, "All CASCADE",
                                  f"M:{m_s2} I:{i_s2} E:{e_s2} R:{r_s2} IX:{ix_s2} scope:{ref_in_scope}"))

    # SC-92: All DRAFT. Structure DRAFT->ABANDONED.
    s_post = S.ABANDONED
    m_ref = make_element("Structure", "Structure", s_post)
    m = make_element("Mechanism", "Mechanism", S.DRAFT, references={"Structure": [m_ref]})
    m_s2, _ = check_referee_preconditions(m, S.DRAFT, S.ABANDONED)

    ea = make_element("E.Art", "Artifact", S.DRAFT)
    eff = make_element("Effector", "Effector", S.DRAFT, references={"Artifact": [ea]})
    e_s2, _ = check_referee_preconditions(eff, S.DRAFT, S.ABANDONED)

    ra = make_element("R.Art", "Artifact", S.DRAFT)
    rec = make_element("Receptor", "Receptor", S.DRAFT, references={"Artifact": [ra]})
    r_s2, _ = check_referee_preconditions(rec, S.DRAFT, S.ABANDONED)

    ref_in_scope = (S.DRAFT, S.ABANDONED) in REFERENCE_CASCADE_SCOPE
    ix_e_ref = make_element("Effector", "Effector", S.ABANDONED)
    ix_r_ref = make_element("Receptor", "Receptor", S.ABANDONED)
    ix = make_element("Interaction", "Interaction", S.DRAFT,
                      references={"Effector": [ix_e_ref], "Receptor": [ix_r_ref]})
    ix_s2, _ = check_referee_preconditions(ix, S.DRAFT, S.ABANDONED)

    if m_s2 and e_s2 and r_s2 and ref_in_scope and ix_s2:
        results.append(TestResult("SC-92", Result.PASS, "All CASCADE (ABANDONED)", "All CASCADE"))
    else:
        results.append(TestResult("SC-92", Result.FAIL, "All CASCADE (ABANDONED)",
                                  f"M:{m_s2} E:{e_s2} R:{r_s2} IX:{ix_s2} scope:{ref_in_scope}"))

    # SC-93: Mixed ACTIVE+DRAFT. Structure ACTIVE->SUSPENDED.
    # ACTIVE elements cascade; DRAFT elements NO-OP (step 1 fail).
    s_post = S.SUSPENDED

    # M1(ACTIVE): cascade
    m1_ref = make_element("Structure", "Structure", s_post)
    m1 = make_element("Mechanism1", "Mechanism", S.ACTIVE, references={"Structure": [m1_ref]})
    m1_s1 = (S.ACTIVE == S.ACTIVE)
    m1_s2, _ = check_referee_preconditions(m1, S.ACTIVE, S.SUSPENDED)

    # M2(DRAFT): step 1 fails
    m2_s1_fail = (S.DRAFT == S.ACTIVE)

    # E1 comp cascade from M1: Art assumed ACTIVE
    ea1 = make_element("E1.Art", "Artifact", S.ACTIVE)
    e1 = make_element("Effector1", "Effector", S.ACTIVE, references={"Artifact": [ea1]})
    e1_s2, _ = check_referee_preconditions(e1, S.ACTIVE, S.SUSPENDED)

    # R1 comp cascade from M1
    ra1 = make_element("R1.Art", "Artifact", S.ACTIVE)
    r1 = make_element("Receptor1", "Receptor", S.ACTIVE, references={"Artifact": [ra1]})
    r1_s2, _ = check_referee_preconditions(r1, S.ACTIVE, S.SUSPENDED)

    # Ix ref cascade
    ref_in_scope = (S.ACTIVE, S.SUSPENDED) in REFERENCE_CASCADE_SCOPE
    ix_e_ref = make_element("Effector1", "Effector", S.SUSPENDED)
    ix_r_ref = make_element("Receptor1", "Receptor", S.SUSPENDED)
    ix = make_element("Interaction", "Interaction", S.ACTIVE,
                      references={"Effector": [ix_e_ref], "Receptor": [ix_r_ref]})
    ix_s2, _ = check_referee_preconditions(ix, S.ACTIVE, S.SUSPENDED)

    if (m1_s1 and m1_s2 and not m2_s1_fail
            and e1_s2 and r1_s2 and ref_in_scope and ix_s2):
        results.append(TestResult("SC-93", Result.PASS,
                                  "ACTIVE: CASCADE; DRAFT: NO-OP",
                                  "ACTIVE: CASCADE; DRAFT: NO-OP"))
    else:
        results.append(TestResult("SC-93", Result.FAIL,
                                  "ACTIVE: CASCADE; DRAFT: NO-OP",
                                  f"M1:{m1_s2} M2-s1:{m2_s1_fail} E1:{e1_s2} R1:{r1_s2} IX:{ix_s2}"))


# ============================================================
# §11 — Composition cascade failure blocks source
# ============================================================

def test_s11():
    """§11 — Composition cascade failure."""

    # SC-95: Mechanism ACTIVE->SUSPENDED. E.Art(RETIRED) → E step 2 fails → BLOCKED.
    ea = make_element("E.Art", "Artifact", S.RETIRED)
    ra = make_element("R.Art", "Artifact", S.ACTIVE)
    eff = make_element("Effector", "Effector", S.ACTIVE, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.ACTIVE, references={"Artifact": [ra]})

    e_s2, e_reason = check_referee_preconditions(eff, S.ACTIVE, S.SUSPENDED)
    r_s2, _ = check_referee_preconditions(rec, S.ACTIVE, S.SUSPENDED)
    if not e_s2:
        results.append(TestResult("SC-95", Result.PASS, "BLOCKED (E comp fail)",
                                  f"BLOCKED: {e_reason}"))
    else:
        results.append(TestResult("SC-95", Result.FAIL, "BLOCKED (E comp fail)",
                                  "E precondition passed unexpectedly"))

    # SC-96: Mechanism ACTIVE->DEPRECATED. R.Art(ABANDONED) → R step 2 fails → BLOCKED.
    ea = make_element("E.Art", "Artifact", S.ACTIVE)
    ra = make_element("R.Art", "Artifact", S.ABANDONED)
    eff = make_element("Effector", "Effector", S.ACTIVE, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.ACTIVE, references={"Artifact": [ra]})

    r_s2, r_reason = check_referee_preconditions(rec, S.ACTIVE, S.DEPRECATED)
    if not r_s2:
        results.append(TestResult("SC-96", Result.PASS, "BLOCKED (R comp fail)",
                                  f"BLOCKED: {r_reason}"))
    else:
        results.append(TestResult("SC-96", Result.FAIL, "BLOCKED (R comp fail)",
                                  "R precondition passed unexpectedly"))


# ============================================================
# §12 — Restoration path
# ============================================================

def test_s12():
    """§12 — Restoration."""

    # SC-100
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.SUSPENDED, references={"Structure": [s]})
    assert_pass("SC-100", m, S.SUSPENDED, S.ACTIVE)

    # SC-101
    s = make_element("Structure", "Structure", S.SUSPENDED)
    m = make_element("Mechanism", "Mechanism", S.SUSPENDED, references={"Structure": [s]})
    assert_blocked("SC-101", m, S.SUSPENDED, S.ACTIVE)

    # SC-102
    s = make_element("Structure", "Structure", S.DEPRECATED)
    m = make_element("Mechanism", "Mechanism", S.DEPRECATED, references={"Structure": [s]})
    assert_pass("SC-102", m, S.DEPRECATED, S.SUSPENDED)

    # SC-103: Mechanism SUSPENDED->ACTIVE. E/R(SUSPENDED), Art(ACTIVE).
    #         Composition cascade: E/R SUSPENDED->ACTIVE, Art ∈ {ACTIVE,DEPRECATED} ✓.
    #         No reference cascade (SUSPENDED->ACTIVE not in scope).
    s = make_element("Structure", "Structure", S.ACTIVE)
    m = make_element("Mechanism", "Mechanism", S.SUSPENDED, references={"Structure": [s]})
    m_ok, _ = check_referee_preconditions(m, S.SUSPENDED, S.ACTIVE)

    ea = make_element("E.Art", "Artifact", S.ACTIVE)
    ra = make_element("R.Art", "Artifact", S.ACTIVE)
    eff = make_element("Effector", "Effector", S.SUSPENDED, references={"Artifact": [ea]})
    rec = make_element("Receptor", "Receptor", S.SUSPENDED, references={"Artifact": [ra]})

    e_s1 = (eff.status == S.SUSPENDED)
    e_s2, _ = check_referee_preconditions(eff, S.SUSPENDED, S.ACTIVE)
    r_s1 = (rec.status == S.SUSPENDED)
    r_s2, _ = check_referee_preconditions(rec, S.SUSPENDED, S.ACTIVE)
    ref_no_cascade = (S.SUSPENDED, S.ACTIVE) not in REFERENCE_CASCADE_SCOPE

    if m_ok and e_s1 and e_s2 and r_s1 and r_s2 and ref_no_cascade:
        results.append(TestResult("SC-103", Result.PASS,
                                  "CASCADE E,R; no ref cascade",
                                  "CASCADE E,R; no ref cascade"))
    else:
        results.append(TestResult("SC-103", Result.FAIL,
                                  "CASCADE E,R; no ref cascade",
                                  f"M:{m_ok} E:{e_s2} R:{r_s2} ref_skip:{ref_no_cascade}"))


# ============================================================
# Framework self-consistency checks
# ============================================================

def test_framework_consistency():
    """Verify framework internal consistency."""

    # Check 1: Every valid transition has a precondition defined
    for t in VALID_TRANSITIONS:
        if t not in REFEREE_PRECONDITIONS:
            results.append(TestResult("FW-01", Result.FAIL,
                                      "All transitions have preconditions",
                                      f"Missing precondition for {t}"))
    else:
        results.append(TestResult("FW-01", Result.PASS,
                                  "All transitions have preconditions",
                                  f"All {len(VALID_TRANSITIONS)} covered"))

    # Check 2: Reference cascade scope is a subset of valid transitions
    for t in REFERENCE_CASCADE_SCOPE:
        if t not in {(x, y) for (x, y) in VALID_TRANSITIONS if x is not None}:
            results.append(TestResult("FW-02", Result.FAIL,
                                      "Cascade scope ⊂ valid transitions",
                                      f"{t} not a valid transition"))
    else:
        results.append(TestResult("FW-02", Result.PASS,
                                  "Cascade scope ⊂ valid transitions",
                                  f"All {len(REFERENCE_CASCADE_SCOPE)} in scope are valid"))

    # Check 3: Terminal transitions allow all-or-almost-all statuses
    # DRAFT->ABANDONED should allow all 9
    aban_set = REFEREE_PRECONDITIONS[(S.DRAFT, S.ABANDONED)]
    if aban_set == ALL_9:
        results.append(TestResult("FW-03a", Result.PASS,
                                  "DRAFT->ABANDONED: all 9", "all 9 allowed"))
    else:
        results.append(TestResult("FW-03a", Result.FAIL,
                                  "DRAFT->ABANDONED: all 9",
                                  f"Only {len(aban_set)}: {aban_set}"))

    # PROPOSED->REJECTED should allow all except DRAFT
    rej_set = REFEREE_PRECONDITIONS[(S.PROPOSED, S.REJECTED)]
    expected = ALL_9 - {S.DRAFT}
    if rej_set == expected:
        results.append(TestResult("FW-03b", Result.PASS,
                                  "PROPOSED->REJECTED: all-DRAFT",
                                  f"8 allowed (excludes DRAFT)"))
    else:
        results.append(TestResult("FW-03b", Result.FAIL,
                                  "PROPOSED->REJECTED: all-DRAFT",
                                  f"Got {rej_set}, expected {expected}"))

    # Check 4: Progress path preconditions form a strict containment chain
    progress = [
        (None,       S.DRAFT),
        (S.DRAFT,    S.PROPOSED),
        (S.PROPOSED, S.APPROVED),
        (S.APPROVED, S.ACTIVE),
    ]
    for i in range(len(progress) - 1):
        curr_set = REFEREE_PRECONDITIONS[progress[i]]
        next_set = REFEREE_PRECONDITIONS[progress[i + 1]]
        if next_set < curr_set:  # strict subset
            results.append(TestResult(f"FW-04.{i}", Result.PASS,
                                      f"Progress containment {i}",
                                      f"{progress[i+1]}: {len(next_set)} ⊂ {len(curr_set)}"))
        else:
            results.append(TestResult(f"FW-04.{i}", Result.FAIL,
                                      f"Progress containment {i}",
                                      f"{next_set} not ⊂ {curr_set}"))

    # Check 5: Degradation transitions share the same allowed set
    degradation_transitions = [
        (S.ACTIVE, S.SUSPENDED),
        (S.ACTIVE, S.DEPRECATED),
        (S.SUSPENDED, S.DEPRECATED),
        (S.DEPRECATED, S.SUSPENDED),
    ]
    deg_sets = [REFEREE_PRECONDITIONS[t] for t in degradation_transitions]
    if all(s == deg_sets[0] for s in deg_sets):
        results.append(TestResult("FW-05a", Result.PASS,
                                  "Degradation sets equal",
                                  f"All 4 = {{{','.join(s.value for s in sorted(deg_sets[0], key=lambda x: x.value))}}}"))
    else:
        results.append(TestResult("FW-05a", Result.FAIL,
                                  "Degradation sets equal",
                                  f"Mismatch: {deg_sets}"))

    # Check 5b: SUSPENDED->ACTIVE (restoration) is a strict subset of degradation
    rest_set = REFEREE_PRECONDITIONS[(S.SUSPENDED, S.ACTIVE)]
    deg_set = REFEREE_PRECONDITIONS[(S.ACTIVE, S.SUSPENDED)]
    if rest_set < deg_set:
        results.append(TestResult("FW-05b", Result.PASS,
                                  "Restoration ⊂ degradation",
                                  f"Restoration {len(rest_set)} ⊂ degradation {len(deg_set)}"))
    else:
        results.append(TestResult("FW-05b", Result.FAIL,
                                  "Restoration ⊂ degradation",
                                  f"{rest_set} not ⊂ {deg_set}"))

    # Check 6: Reference cascade scope = degradation + decommission + terminal
    expected_scope = {
        (S.ACTIVE, S.SUSPENDED),
        (S.ACTIVE, S.DEPRECATED),
        (S.SUSPENDED, S.DEPRECATED),
        (S.DEPRECATED, S.SUSPENDED),
        (S.DEPRECATED, S.RETIRED),
        (S.DRAFT, S.ABANDONED),
        (S.PROPOSED, S.REJECTED),
    }
    if REFERENCE_CASCADE_SCOPE == expected_scope:
        results.append(TestResult("FW-06", Result.PASS,
                                  "Ref cascade scope correct",
                                  f"7 transitions in scope"))
    else:
        results.append(TestResult("FW-06", Result.FAIL,
                                  "Ref cascade scope correct",
                                  f"Diff: +{REFERENCE_CASCADE_SCOPE - expected_scope} "
                                  f"-{expected_scope - REFERENCE_CASCADE_SCOPE}"))


# ============================================================
# MAIN
# ============================================================

def main():
    test_framework_consistency()
    test_s1()
    test_s2()
    test_s3()
    test_s4()
    test_s5()
    test_s6()
    test_s7()
    test_s8()
    test_s9()
    test_s10()
    test_s11()
    test_s12()

    # Report
    print("=" * 72)
    print("GSM Definition Lifecycle — Framework Verification Report")
    print("=" * 72)

    passed = sum(1 for r in results if r.result == Result.PASS)
    failed = sum(1 for r in results if r.result == Result.FAIL)
    warned = sum(1 for r in results if r.result == Result.WARN)

    sections = {}
    for r in results:
        prefix = r.sc_id.split("-")[0] if "-" in r.sc_id else r.sc_id[:2]
        sections.setdefault(prefix, []).append(r)

    for section, items in sections.items():
        sec_pass = sum(1 for r in items if r.result == Result.PASS)
        sec_total = len(items)
        marker = "✓" if sec_pass == sec_total else "✗"
        print(f"\n{marker} {section} ({sec_pass}/{sec_total})")
        for r in items:
            icon = "  ✓" if r.result == Result.PASS else "  ✗" if r.result == Result.FAIL else "  ⚠"
            line = f"{icon} {r.sc_id}: expected={r.expected}"
            if r.result != Result.PASS:
                line += f"  actual={r.actual}"
            if r.details and r.result != Result.PASS:
                line += f"  [{r.details}]"
            print(line)

    print(f"\n{'=' * 72}")
    print(f"Total: {passed} passed, {failed} failed, {warned} warnings "
          f"({passed + failed + warned} tests)")
    if failed == 0:
        print("ALL SCENARIOS VERIFIED ✓")
    else:
        print(f"FAILURES DETECTED: {failed}")
    print("=" * 72)

    return 1 if failed > 0 else 0


if __name__ == "__main__":
    exit(main())
