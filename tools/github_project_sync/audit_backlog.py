#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, DefaultDict, Iterable


BACKLOG_EXPORT_PATH = Path("definition/features/backlog/backlog-export.json")


@dataclass(frozen=True)
class AuditResult:
    total_items: int
    kinds: Counter
    requires_edges: int
    bad_requires_edges: list[tuple[str, str, str]]
    capabilities_missing_priority: list[str]
    capabilities_missing_complexity: list[str]
    capabilities_missing_feasibility: list[str]
    usecases_missing_mapping: list[str]
    features_with_eval_fields: list[tuple[str, str, str | None, str | None]]


def _load_export(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _get_fields(item: dict[str, Any]) -> dict[str, Any]:
    fields = item.get("fields")
    return fields if isinstance(fields, dict) else {}


def _nonempty(fields: dict[str, Any], key: str) -> bool:
    return key in fields and fields[key] not in (None, "", [])


def audit_backlog(items: list[dict[str, Any]]) -> AuditResult:
    by_id = {i["localId"]: i for i in items}
    kinds = Counter(i["kind"] for i in items)

    # Requires-edge sanity: references exist and (by convention) point to Enabler Epics.
    bad_requires: list[tuple[str, str, str]] = []
    requires_edges = 0
    for it in items:
        req = _get_fields(it).get("requires")
        if not req:
            continue
        for dep in req:
            requires_edges += 1
            dep_it = by_id.get(dep)
            if not dep_it:
                bad_requires.append((it["localId"], dep, "missing"))
                continue
            if dep_it["kind"] != "EnablerEpic":
                bad_requires.append((it["localId"], dep, dep_it["kind"]))

    capabilities_missing_priority: list[str] = []
    capabilities_missing_complexity: list[str] = []
    capabilities_missing_feasibility: list[str] = []

    for it in items:
        if it["kind"] != "Capability":
            continue
        f = _get_fields(it)
        if not _nonempty(f, "priorityBucket"):
            capabilities_missing_priority.append(it["localId"])
        if not _nonempty(f, "complexity"):
            capabilities_missing_complexity.append(it["localId"])
        if not _nonempty(f, "feasibility"):
            capabilities_missing_feasibility.append(it["localId"])

    usecases_missing_mapping: list[str] = []
    for it in items:
        if it["kind"] != "UseCase":
            continue
        f = _get_fields(it)
        # In the current model, UC maps to capability-level by name.
        if not _nonempty(f, "capability") or not _nonempty(f, "feature") or not _nonempty(f, "epicId"):
            usecases_missing_mapping.append(it["localId"])

    features_with_eval_fields: list[tuple[str, str, str | None, str | None]] = []
    for it in items:
        if it["kind"] != "Feature":
            continue
        f = _get_fields(it)
        if "complexity" in f or "feasibility" in f:
            features_with_eval_fields.append(
                (it["localId"], it["title"], f.get("complexity"), f.get("feasibility"))
            )

    return AuditResult(
        total_items=len(items),
        kinds=kinds,
        requires_edges=requires_edges,
        bad_requires_edges=bad_requires,
        capabilities_missing_priority=capabilities_missing_priority,
        capabilities_missing_complexity=capabilities_missing_complexity,
        capabilities_missing_feasibility=capabilities_missing_feasibility,
        usecases_missing_mapping=usecases_missing_mapping,
        features_with_eval_fields=features_with_eval_fields,
    )


def _capability_uc_coverage(items: list[dict[str, Any]]) -> tuple[int, list[tuple[str, str]]]:
    cap_titles = {it["title"]: it["localId"] for it in items if it["kind"] == "Capability"}
    uc_by_cap_title: DefaultDict[str, list[str]] = defaultdict(list)
    for it in items:
        if it["kind"] != "UseCase":
            continue
        cap = _get_fields(it).get("capability")
        if isinstance(cap, str) and cap.strip():
            uc_by_cap_title[cap].append(it["localId"])

    missing: list[tuple[str, str]] = []
    for title, lid in cap_titles.items():
        if title not in uc_by_cap_title:
            missing.append((lid, title))

    return len(cap_titles), missing


def render_markdown(result: AuditResult, items: list[dict[str, Any]]) -> str:
    cap_total, caps_missing_uc = _capability_uc_coverage(items)

    lines: list[str] = []
    lines.append("# SIE Features Backlog Coherence Audit")
    lines.append("")
    lines.append("This is a *design-time* coherence check over `backlog-export.json`. It focuses on:")
    lines.append("- **Functional grounding** (UseCase coverage and UC mapping completeness)")
    lines.append("- **Value** (market value bucket coverage)")
    lines.append("- **Feasibility** (complexity + feasibility tier coverage)")
    lines.append("")

    lines.append("## Inventory")
    lines.append("")
    lines.append(f"- Total items: **{result.total_items}**")
    lines.append("- By kind:")
    for k in sorted(result.kinds):
        lines.append(f"  - {k}: **{result.kinds[k]}**")
    lines.append("")

    lines.append("## Functional")
    lines.append("")
    lines.append("### Capability ↔ UseCase coverage")
    lines.append("")
    if not caps_missing_uc:
        lines.append(f"- All **{cap_total}** Capabilities have at least one UseCase (by title match).")
    else:
        lines.append(f"- Capabilities missing a UseCase: **{len(caps_missing_uc)} / {cap_total}**")
        for lid, title in caps_missing_uc[:20]:
            lines.append(f"  - {lid} ({title})")
    lines.append("")

    lines.append("### UseCase mapping completeness")
    lines.append("")
    if not result.usecases_missing_mapping:
        lines.append("- All UseCases have `epicId` + `capability` + `feature` mapping fields.")
    else:
        lines.append(f"- UseCases missing mapping fields: **{len(result.usecases_missing_mapping)}**")
        for lid in result.usecases_missing_mapping[:20]:
            lines.append(f"  - {lid}")
    lines.append("")

    lines.append("## Value")
    lines.append("")
    lines.append("In the current models, **Market Value buckets (P0–P3) are assigned at Capability level**")
    lines.append("via packages in `sie-analytics-market-value-v1.puml`. That’s why Features/Epics/Enablers")
    lines.append("do not carry `priorityBucket` fields in the export.")
    lines.append("")
    if not result.capabilities_missing_priority:
        lines.append("- All Capabilities have a `priorityBucket`.")
    else:
        lines.append(f"- Capabilities missing `priorityBucket`: **{len(result.capabilities_missing_priority)}**")
        for lid in result.capabilities_missing_priority[:20]:
            lines.append(f"  - {lid}")
    lines.append("")

    lines.append("## Feasibility")
    lines.append("")
    lines.append("In the current models, **Complexity + Feasibility tiers are assigned at Capability level**")
    lines.append("via packages/notes in `sie-analytics-feature-evaluation-v1.puml`, and additionally for UseCases")
    lines.append("via UC notes in `analytics-usecases/*.puml`.")
    lines.append("")
    if not result.capabilities_missing_complexity and not result.capabilities_missing_feasibility:
        lines.append("- All Capabilities have `complexity` and `feasibility`.")
    else:
        lines.append(
            f"- Capabilities missing complexity: **{len(result.capabilities_missing_complexity)}**; "
            f"missing feasibility: **{len(result.capabilities_missing_feasibility)}**"
        )
    lines.append("")

    if result.features_with_eval_fields:
        lines.append("### Feature-level evaluation exceptions")
        lines.append("")
        lines.append("Most Features do not carry evaluation fields. The export currently includes eval fields for:")
        for lid, title, complexity, feas in result.features_with_eval_fields:
            lines.append(f"- {lid} ({title}): complexity={complexity}, feasibility={feas}")
        lines.append("")

    lines.append("## Dependency coherence (`requires`)\n")
    lines.append(f"- Requires edges exported: **{result.requires_edges}**")
    if not result.bad_requires_edges:
        lines.append("- All `requires` references resolve and point to Enabler Epics (runway).")
    else:
        lines.append(f"- Bad `requires` edges: **{len(result.bad_requires_edges)}**")
        for src, dep, kind in result.bad_requires_edges[:20]:
            lines.append(f"  - {src} -> {dep} ({kind})")
    lines.append("")

    lines.append("## Coherence notes / options")
    lines.append("")
    lines.append("- If you want **Feature-level** value/feasibility (instead of Capability-level), the smallest")
    lines.append("  coherent change is: keep the Capability buckets as *defaults* and add **explicit overrides**")
    lines.append("  only where needed (to avoid exploding the evaluation/mkt-value diagrams).")
    lines.append("- Enabler items currently act as *prerequisite runway*; adding evaluation notes for EN-1/EN-2/EN-3")
    lines.append("  (complexity/feasibility only) would improve planning without conflating them with market value.")

    return "\n".join(lines)


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Design-time coherence audit for backlog-export.json")
    parser.add_argument(
        "--export",
        default=str(BACKLOG_EXPORT_PATH),
        help="Path to backlog-export.json (default: definition/features/backlog/backlog-export.json)",
    )
    parser.add_argument("--out", help="Write Markdown report to this path (default: stdout)")
    args = parser.parse_args(list(argv) if argv is not None else None)

    export_path = Path(args.export)
    export = _load_export(export_path)
    items = export.get("items")
    if not isinstance(items, list):
        raise SystemExit("Invalid export: missing 'items' array")

    result = audit_backlog(items)
    report = render_markdown(result, items)

    if args.out:
        Path(args.out).write_text(report + "\n", encoding="utf-8")
    else:
        print(report)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
