#!/usr/bin/env python3

import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple


@dataclass
class Item:
    local_id: str
    title: str
    kind: str  # Epic | Capability | Feature | Story | UseCase | EnablerEpic | EnablerCapability | EnablerFeature
    parent_id: Optional[str]
    fields: Dict[str, object]
    source: str


PACKAGE_RE = re.compile(
    r'^\s*package\s+"(?P<name>[^"]+)"\s*(?:<<(?P<stereotypes>[^>]+)>>)?\s*as\s+(?P<alias>[A-Za-z0-9_]+)\s*(?P<brace>\{)?\s*$'
)

NOTE_START_RE = re.compile(r'^\s*note\b')
NOTE_END_RE = re.compile(r'^\s*end\s+note\b')

DEPENDENCY_RE = re.compile(r'^\s*(?P<src>[A-Za-z0-9_]+)\s*\.\.>\s*(?P<dst>[A-Za-z0-9_]+)\s*:\s*(?P<label>.+?)\s*$')

ACTOR_RE = re.compile(
    r'^\s*actor\s+"(?P<name>[^"]+)"\s+as\s+(?P<alias>[A-Za-z0-9_]+)\s*(?P<stereotype><<[^>]+>>)?\s*$'
)
USECASE_RE = re.compile(
    r'^\s*usecase\s+"(?P<label>[^"]+)"\s+as\s+(?P<alias>[A-Za-z0-9_]+)\s*$'
)
REL_RE = re.compile(r'^\s*(?P<src>[A-Za-z0-9_]+)\s*-->\s*(?P<dst>[A-Za-z0-9_]+)\s*$')


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _normalize_stereotypes(raw: Optional[str]) -> List[str]:
    if not raw:
        return []
    parts = [p.strip() for p in raw.split(",")]
    return [p for p in parts if p]


def _extract_bucket_block(text: str) -> Dict[str, str]:
    # Map package names -> bucket (P0/P1/P2/P3) from the market value diagram.
    # We do not attempt to parse arbitrary nesting; we just track current top-level bucket package.
    bucket_by_feature: Dict[str, str] = {}
    current_bucket: Optional[str] = None

    for line in text.splitlines():
        m = PACKAGE_RE.match(line)
        if not m:
            continue

        name = m.group("name")
        stereos = _normalize_stereotypes(m.group("stereotypes"))

        # Detect bucket headers by convention: "P0 - ...", etc.
        bucket_m = re.match(r"^(P[0-3])\s*-\s+", name)
        if bucket_m and ("Epic" not in stereos):
            current_bucket = bucket_m.group(1)
            continue

        # Within a bucket, feature packages have names matching our analytics feature packages.
        if current_bucket and name and not re.match(r"^P[0-3]\s*-", name):
            bucket_by_feature[name] = current_bucket

    return bucket_by_feature


def _extract_eval_fields(text: str) -> Dict[str, Dict[str, str]]:
    # Map package name -> {complexity, feasibility}
    # The evaluation view writes notes that contain lines like:
    #   **Complexity:** MED
    #   **Feasibility:** NOW
    fields: Dict[str, Dict[str, str]] = {}

    current_pkg: Optional[str] = None
    in_note = False
    note_lines: List[str] = []

    for line in text.splitlines():
        m = PACKAGE_RE.match(line)
        if m:
            current_pkg = m.group("name")
            continue

        if NOTE_START_RE.match(line):
            in_note = True
            note_lines = []
            continue

        if in_note:
            if NOTE_END_RE.match(line):
                in_note = False
                complexity = None
                feasibility = None
                for nl in note_lines:
                    cm = re.search(r"\*\*Complexity:\*\*\s*(.+)$", nl.strip())
                    if cm:
                        complexity = cm.group(1).strip()
                    fm = re.search(r"\*\*Feasibility:\*\*\s*(.+)$", nl.strip())
                    if fm:
                        feasibility = fm.group(1).strip().split(" ")[0]
                if current_pkg and (complexity or feasibility):
                    fields[current_pkg] = {}
                    if complexity:
                        fields[current_pkg]["complexity"] = complexity
                    if feasibility:
                        fields[current_pkg]["feasibility"] = feasibility
                continue
            note_lines.append(line)

    return fields


def _safe_id_from_title(title: str) -> str:
    # Prefer leading "E-1" / "EN-1" token if present
    m = re.match(r"^(EN-|E-)\d+", title)
    if m:
        return m.group(0)

    # Otherwise slug
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", title).strip("-").lower()
    return slug[:80]


def _norm_token(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", s.lower())


def _parse_safe_backlog(path: Path) -> List[Item]:
    text = _read_text(path)

    items: List[Item] = []
    stack: List[Tuple[str, str, List[str]]] = []  # (local_id, title, stereotypes)
    alias_to_local_id: Dict[str, str] = {}
    local_id_to_item: Dict[str, Item] = {}

    for line in text.splitlines():
        m = PACKAGE_RE.match(line)
        if m:
            name = m.group("name")
            stereos = _normalize_stereotypes(m.group("stereotypes"))
            alias = m.group("alias")

            parent_id = stack[-1][0] if stack else None

            # Determine kind based on stereotypes
            kind = "Feature"
            if "Epic" in stereos and "Enabler" in stereos:
                kind = "EnablerEpic"
            elif "Epic" in stereos:
                kind = "Epic"
            elif "Capability" in stereos and "Enabler" in stereos:
                kind = "EnablerCapability"
            elif "Capability" in stereos:
                kind = "Capability"
            elif "Story" in stereos:
                kind = "Story"
            elif "Feature" in stereos and "Enabler" in stereos:
                kind = "EnablerFeature"
            elif "Feature" in stereos:
                kind = "Feature"

            local_id = _safe_id_from_title(name)
            # For nested non-epic items, disambiguate by parent
            if parent_id and kind not in ("Epic", "EnablerEpic"):
                local_id = f"{parent_id}/{local_id}"

            items.append(
                Item(
                    local_id=local_id,
                    title=name,
                    kind=kind,
                    parent_id=parent_id,
                    fields={},
                    source=str(path.name),
                )
            )

            alias_to_local_id[alias] = local_id
            local_id_to_item[local_id] = items[-1]

            if m.group("brace"):
                stack.append((local_id, name, stereos))
            continue

        if line.strip() == "}":
            if stack:
                stack.pop()

    # Parse dependency links (requires) from the bottom of the diagram.
    # Example: E1 ..> EN1 : requires
    for line in text.splitlines():
        dm = DEPENDENCY_RE.match(line)
        if not dm:
            continue
        label = (dm.group("label") or "").strip().lower()
        if "requires" not in label:
            continue
        src_alias = dm.group("src")
        dst_alias = dm.group("dst")
        src_id = alias_to_local_id.get(src_alias)
        dst_id = alias_to_local_id.get(dst_alias)
        if not src_id or not dst_id:
            continue
        src_item = local_id_to_item.get(src_id)
        if not src_item:
            continue
        req = src_item.fields.setdefault("requires", [])
        if isinstance(req, list) and dst_id not in req:
            req.append(dst_id)

    return items


def _extract_uc_mapping(text: str) -> Optional[Tuple[str, str, str]]:
    # Parse the SAFe mapping we added in FeasibilityNote
    #  **SAFe mapping:** Epic E-1 ...
    #  Capability: X
    #  Feature: Y
    epic_id = None
    cap = None
    feat = None
    for line in text.splitlines():
        if "**SAFe mapping:**" in line:
            m = re.search(r"Epic\s+(EN-|E-)\d+", line)
            if m:
                epic_id = m.group(0).split()[-1]
        m2 = re.search(r"^\s*Capability:\s*(.+)\s*$", line)
        if m2:
            cap = m2.group(1).strip()
        m3 = re.search(r"^\s*Feature:\s*(.+)\s*$", line)
        if m3:
            feat = m3.group(1).strip()
    if epic_id and cap and feat:
        return epic_id, cap, feat
    return None


def _extract_uc_details(text: str) -> Dict[str, object]:
    # Parse fields from the FeasibilityNote blocks we add to each UC diagram.
    # We keep this deliberately best-effort (design-time models evolve).
    details: Dict[str, object] = {}
    in_note = False
    note_lines: List[str] = []

    for line in text.splitlines():
        if NOTE_START_RE.match(line):
            in_note = True
            note_lines = []
            continue
        if in_note:
            if NOTE_END_RE.match(line):
                in_note = False
                break
            note_lines.append(line.rstrip())

    if not note_lines:
        return details

    raw = "\n".join(note_lines)
    details["note"] = raw.strip()

    m_plane = re.search(r"\*\*Plane ownership:\*\*\s*([^\n]+)", raw)
    if m_plane:
        details["planeOwnership"] = m_plane.group(1).strip()

    m_req = re.search(r"\*\*Requires capabilities:\*\*\s*([^\n]+)", raw)
    if m_req:
        caps = [c.strip() for c in m_req.group(1).split(",") if c.strip()]
        details["requiredCapabilities"] = caps

    m_deps = re.search(r"\*\*Hard dependencies:\*\*\s*([^\n]+)", raw)
    if m_deps:
        deps = [d.strip() for d in m_deps.group(1).split(",") if d.strip()]
        details["hardDependencies"] = deps

    m_feas = re.search(r"\*\*Feasibility tier:\*\*\s*([A-Z]+)", raw)
    if m_feas:
        details["feasibility"] = m_feas.group(1).strip()

    m_safe_type = re.search(r"\*\*SAFe mapping:\*\*\s*Epic\s+((?:EN-|E-)\d+)", raw)
    if m_safe_type:
        details["epicId"] = m_safe_type.group(1)

    return details


def _extract_uc_scenarios(text: str, max_scenarios: int = 6) -> List[str]:
    # Ground scenarios in modeled actor -> usecase interactions.
    # We prefer human actors (exclude <<system>>).
    actors: Dict[str, Dict[str, object]] = {}
    usecases: Dict[str, str] = {}
    rels: List[Tuple[str, str]] = []

    for line in text.splitlines():
        am = ACTOR_RE.match(line)
        if am:
            name = am.group("name").strip()
            alias = am.group("alias").strip()
            stereo = (am.group("stereotype") or "").lower()
            is_system = "system" in stereo
            actors[alias] = {"name": name, "isSystem": is_system}
            continue

        um = USECASE_RE.match(line)
        if um:
            label = um.group("label").replace("\\n", " ").strip()
            label = re.sub(r"\s+", " ", label)
            usecases[um.group("alias").strip()] = label
            continue

        rm = REL_RE.match(line)
        if rm:
            rels.append((rm.group("src"), rm.group("dst")))

    scenarios: List[str] = []
    seen = set()
    for src, dst in rels:
        a = actors.get(src)
        uc_label = usecases.get(dst)
        if not a or a.get("isSystem"):
            continue
        if not uc_label:
            continue
        key = (a["name"], uc_label)
        if key in seen:
            continue
        seen.add(key)
        scenarios.append(f"As {a['name']}, {uc_label}.")
        if len(scenarios) >= max_scenarios:
            break

    return scenarios


def _parse_usecases(usecase_dir: Path) -> List[Item]:
    items: List[Item] = []
    for p in sorted(usecase_dir.glob("*.puml")):
        txt = _read_text(p)
        mapping = _extract_uc_mapping(txt)
        if not mapping:
            continue
        epic_id, cap, feat = mapping
        details = _extract_uc_details(txt)
        scenarios = _extract_uc_scenarios(txt)
        # UseCase local_id stable by filename
        uc_id = f"UC:{p.stem}"
        # Parent = the epic id; this keeps GitHub grouping manageable
        parent_id = epic_id
        title = f"UC - {p.stem.replace('-', ' ')}"
        items.append(
            Item(
                local_id=uc_id,
                title=title,
                kind="UseCase",
                parent_id=parent_id,
                fields={
                    "capability": cap,
                    "feature": feat,
                    **details,
                    **({"scenarios": scenarios} if scenarios else {}),
                },
                source=str(p.relative_to(usecase_dir.parent)),
            )
        )
    return items


def export(config_path: Path) -> Dict[str, object]:
    cfg = json.loads(_read_text(config_path))

    base = config_path.parent
    safe_backlog = (base / cfg["sources"]["safeBacklogModel"]).resolve()
    market_value = (base / cfg["sources"]["marketValueModel"]).resolve()
    evaluation = (base / cfg["sources"]["evaluationModel"]).resolve()
    usecase_dir = (base / cfg["sources"]["usecaseDir"]).resolve()

    items = _parse_safe_backlog(safe_backlog)
    items.extend(_parse_usecases(usecase_dir))

    bucket_by_feature = _extract_bucket_block(_read_text(market_value))
    eval_by_pkg = _extract_eval_fields(_read_text(evaluation))

    # Enrich items with market bucket + complexity + feasibility when name matches
    for it in items:
        # Priority bucket applies to analytics capability-level package titles
        bucket = bucket_by_feature.get(it.title)
        if bucket:
            it.fields["priorityBucket"] = bucket

        ev = eval_by_pkg.get(it.title)
        if ev:
            if "complexity" in ev:
                it.fields["complexity"] = ev["complexity"]
            if "feasibility" in ev:
                it.fields["feasibility"] = ev["feasibility"]

        # Stories (UseCases) inherit complexity/feasibility from their mapped capability when absent.
        if it.kind == "UseCase":
            if "complexity" not in it.fields and it.fields.get("capability"):
                cap_name = str(it.fields.get("capability"))
                cap_ev = eval_by_pkg.get(cap_name)
                if cap_ev and "complexity" in cap_ev:
                    it.fields["complexity"] = cap_ev["complexity"]
            if "feasibility" not in it.fields and it.fields.get("capability"):
                cap_name = str(it.fields.get("capability"))
                cap_ev = eval_by_pkg.get(cap_name)
                if cap_ev and "feasibility" in cap_ev:
                    it.fields["feasibility"] = cap_ev["feasibility"]

    out = {
        "schema": "sif.features.backlog.export.v1",
        "config": {
            "org": cfg["org"],
            "projectNumber": cfg["projectNumber"],
        },
        "items": [
            {
                "localId": i.local_id,
                "title": i.title,
                "kind": i.kind,
                "parentId": i.parent_id,
                "fields": i.fields,
                "source": i.source,
            }
            for i in items
        ],
    }
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: export_backlog.py /abs/path/to/github-project-sync-config.json", file=sys.stderr)
        return 2

    cfg_path = Path(sys.argv[1]).resolve()
    out = export(cfg_path)
    print(json.dumps(out, indent=2, sort_keys=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
