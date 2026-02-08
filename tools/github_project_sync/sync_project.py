#!/usr/bin/env python3

import json
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple


def _run(cmd: List[str]) -> str:
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(cmd)}\n{p.stderr.strip()}")
    return p.stdout


def _gh_graphql(query: str, variables: Dict[str, object]) -> Dict[str, object]:
    # NOTE: This repo runs on older gh versions; passing a JSON `variables={...}` payload
    # is not reliably supported. Instead, pass variables via -F/-f key=value.
    args = ["gh", "api", "graphql", "-f", f"query={query}"]
    for k, v in variables.items():
        # gh `-F` performs JSON coercion (numbers become numbers). For GraphQL
        # variables typed as String/ID, we must keep them as strings. Use `-f`
        # for strings and `-F` for numbers/bools/objects.
      if v is None:
        args.extend(["-F", f"{k}=null"])
        continue
      if isinstance(v, (dict, list)):
        args.extend(["-F", f"{k}={json.dumps(v)}"])
      elif isinstance(v, bool):
        args.extend(["-F", f"{k}={'true' if v else 'false'}"])
      elif isinstance(v, (int, float)):
        args.extend(["-F", f"{k}={v}"])
      else:
        args.extend(["-f", f"{k}={str(v)}"])

    try:
        out = _run(args)
    except RuntimeError as e:
        msg = str(e)
        if "INSUFFICIENT_SCOPES" in msg and ("read:project" in msg or "project" in msg):
            raise RuntimeError(
                msg
                + "\n\nMissing GitHub Projects scopes. Fix by running:\n"
                  "  gh auth refresh -h github.com -s read:project,project\n"
                  "Then re-run the sync."
            )
        raise

    return json.loads(out)


def _load_json(path: Path) -> Dict[str, object]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def _save_json(path: Path, data: Dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=False) + "\n", encoding="utf-8")


def _get_project(org: str, project_number: int) -> Tuple[str, Dict[str, str]]:
    query = """
    query($org: String!, $number: Int!) {
      organization(login: $org) {
        projectV2(number: $number) {
          id
          fields(first: 50) {
            nodes {
              ... on ProjectV2FieldCommon {
                id
                name
              }
              ... on ProjectV2SingleSelectField {
                id
                name
                options { id name }
              }
            }
          }
        }
      }
    }
    """
    data = _gh_graphql(query, {"org": org, "number": project_number})
    proj = data["data"]["organization"]["projectV2"]
    if not proj:
        raise RuntimeError(f"ProjectV2 not found: {org} #{project_number}")

    field_id_by_name: Dict[str, str] = {}
    for node in proj["fields"]["nodes"]:
        if node and node.get("name") and node.get("id"):
            field_id_by_name[node["name"]] = node["id"]

    return proj["id"], field_id_by_name


def _create_text_field(project_id: str, name: str) -> str:
    # Create a TEXT field (safe default) if missing.
    query = """
    mutation($projectId: ID!, $name: String!) {
      createProjectV2Field(input: { projectId: $projectId, name: $name, dataType: TEXT }) {
        projectV2Field { ... on ProjectV2FieldCommon { id name } }
      }
    }
    """
    data = _gh_graphql(query, {"projectId": project_id, "name": name})
    field = data["data"]["createProjectV2Field"]["projectV2Field"]
    return field["id"]


def _ensure_field(project_id: str, field_ids: Dict[str, str], name: Optional[str]) -> Optional[str]:
    if not name:
        return None
    if name in field_ids:
        return field_ids[name]
    try:
        fid = _create_text_field(project_id, name)
        field_ids[name] = fid
        return fid
    except Exception:
        # If field creation isn't permitted / schema differs, leave it to manual creation.
        return None


def _get_project_item_content(item_id: str) -> Dict[str, object]:
    query = """
    query($itemId: ID!) {
      node(id: $itemId) {
        ... on ProjectV2Item {
          id
          content {
            __typename
            ... on DraftIssue { id title body }
            ... on Issue { id title body }
            ... on PullRequest { id title body }
          }
        }
      }
    }
    """
    data = _gh_graphql(query, {"itemId": item_id})
    node = data.get("data", {}).get("node") or {}
    content = node.get("content") or {}
    typename = content.get("__typename") if isinstance(content, dict) else None
    return {
        "contentType": typename,
        "contentId": content.get("id") if isinstance(content, dict) else None,
        "title": content.get("title") if isinstance(content, dict) else None,
        "body": content.get("body") if isinstance(content, dict) else None,
    }


def _update_draft_issue(draft_issue_id: str, title: str, body: str) -> None:
    query = """
    mutation($draftIssueId: ID!, $title: String!, $body: String!) {
      updateProjectV2DraftIssue(input: { draftIssueId: $draftIssueId, title: $title, body: $body }) {
      draftIssue { id }
      }
    }
    """
    _gh_graphql(query, {"draftIssueId": draft_issue_id, "title": title, "body": body})


def _safe_type_for_kind(kind: Optional[str]) -> str:
    if kind == "UseCase":
        return "Story"
    if kind == "Story":
        return "Story"
    if kind in ("EnablerEpic", "EnablerCapability", "EnablerFeature"):
        return "Enabler"
    if kind in ("Epic", "Capability", "Feature"):
        return kind
    return "Feature"


def _norm_token(s: object) -> str:
    return "".join([c for c in str(s).lower() if c.isalnum()])


def _child_items(local_id: str, by_local_id: Dict[str, Dict[str, object]]) -> List[Dict[str, object]]:
    children: List[Dict[str, object]] = []
    for it in by_local_id.values():
        if str(it.get("parentId")) == local_id:
            children.append(it)
    children.sort(key=lambda x: (str(x.get("kind")), str(x.get("localId"))))
    return children


def _resolve_local_ids(ids: object, by_local_id: Dict[str, Dict[str, object]]) -> List[str]:
    if not ids:
        return []
    if not isinstance(ids, list):
        ids = [ids]
    out: List[str] = []
    for i in ids:
        lid = str(i)
        it = by_local_id.get(lid)
        if it:
            out.append(f"{it.get('kind')} {it.get('localId')}: {it.get('title')}")
        else:
            out.append(lid)
    return out


def _collect_scenarios(item: Dict[str, object], by_local_id: Dict[str, Dict[str, object]], limit: int = 3) -> List[str]:
    local_id = str(item.get("localId"))
    title = str(item.get("title"))
    kind = str(item.get("kind"))

    scenarios: List[str] = []
    seen = set()

    def _add_from_uc(uc: Dict[str, object]) -> None:
        sc = (uc.get("fields") or {}).get("scenarios")
        if not isinstance(sc, list):
            return
        for s in sc:
            ss = str(s).strip()
            if not ss:
                continue
            if ss in seen:
                continue
            seen.add(ss)
            scenarios.append(ss)
            if len(scenarios) >= limit:
                return

    # For story/usecase items: scenarios are directly attached
    if kind == "UseCase":
        _add_from_uc(item)
        return scenarios[:limit]

    norm_title = _norm_token(title)

    for it in by_local_id.values():
        if str(it.get("kind")) != "UseCase":
            continue
        f = it.get("fields") or {}
        # Epic match: UC parentId is set to epic id by exporter
        if kind in ("Epic", "EnablerEpic"):
            if str(it.get("parentId")) == local_id or str(f.get("epicId")) == local_id:
                _add_from_uc(it)
        # Capability match
        elif kind in ("Capability", "EnablerCapability"):
            if _norm_token(f.get("capability")) == norm_title:
                _add_from_uc(it)
        # Feature match
        elif kind in ("Feature", "EnablerFeature"):
            if _norm_token(f.get("feature")) == norm_title:
                _add_from_uc(it)

        if len(scenarios) >= limit:
            break

    return scenarios[:limit]


def _build_description(item: Dict[str, object], by_local_id: Dict[str, Dict[str, object]]) -> str:
    local_id = str(item.get("localId"))
    title = str(item.get("title"))
    kind = str(item.get("kind"))
    parent_id = item.get("parentId")
    fields = item.get("fields") or {}
    source = str(item.get("source"))

    safe_type = _safe_type_for_kind(kind)

    # Resolve attachment chain (best-effort)
    chain: List[str] = []
    cur = parent_id
    seen = set()
    while cur and cur not in seen:
        seen.add(cur)
        parent = by_local_id.get(str(cur))
        if not parent:
            chain.append(str(cur))
            break
        chain.append(f"{parent.get('kind')} {parent.get('localId')}: {parent.get('title')}")
        cur = parent.get("parentId")

    bucket = fields.get("priorityBucket")
    feasibility = fields.get("feasibility")
    complexity = fields.get("complexity")

    cap = fields.get("capability")
    feat = fields.get("feature")

    plane = fields.get("planeOwnership")
    req_caps = fields.get("requiredCapabilities")
    hard_deps = fields.get("hardDependencies")

    requires = fields.get("requires")

    note = fields.get("note")

    lines: List[str] = []
    lines.append(f"SAFe Type: {safe_type}")
    lines.append(f"Local Id: {local_id}")
    lines.append(f"Source: {source}")

    if chain:
        lines.append("Attached to:")
        for c in chain:
            lines.append(f"- {c}")

    children = _child_items(local_id, by_local_id)
    if children:
        lines.append("Decomposes into:")
        for ch in children:
            lines.append(f"- {ch.get('kind')} {ch.get('localId')}: {ch.get('title')}")

    req_list = _resolve_local_ids(requires, by_local_id)
    if req_list:
        lines.append("Requires (dependencies):")
        for r in req_list:
            lines.append(f"- {r}")

    if cap:
        lines.append(f"Capability: {cap}")
    if feat:
        lines.append(f"Feature: {feat}")

    if bucket:
        lines.append(f"Priority bucket: {bucket}")
    if feasibility:
        lines.append(f"Feasibility tier: {feasibility}")
    if complexity:
        lines.append(f"Complexity: {complexity}")

    if plane:
        lines.append(f"Plane ownership: {plane}")
    if req_caps:
        if isinstance(req_caps, list):
            lines.append("Required capabilities: " + ", ".join([str(x) for x in req_caps]))
        else:
            lines.append(f"Required capabilities: {req_caps}")
    if hard_deps:
        if isinstance(hard_deps, list):
            lines.append("Hard dependencies: " + ", ".join([str(x) for x in hard_deps]))
        else:
            lines.append(f"Hard dependencies: {hard_deps}")

    # Concrete scenarios are grounded in UC models; include for non-story items too.
    scenario_lines = _collect_scenarios(item, by_local_id, limit=3)
    if scenario_lines:
        lines.append("Concrete story scenarios (from UC models):")
        for s in scenario_lines:
            lines.append(f"- {s}")

    if note and safe_type == "Story":
      lines.append("\n---\n")
      lines.append("Design note:")
      lines.append(str(note))

    return "\n".join(lines).strip() + "\n"


def _find_item_by_title(project_id: str, title: str) -> Optional[str]:
    # Best-effort search by title. Paginate to avoid the `first: 100` cap.
    query = """
    query($projectId: ID!, $cursor: String) {
      node(id: $projectId) {
        ... on ProjectV2 {
          items(first: 100, after: $cursor) {
            pageInfo { hasNextPage endCursor }
            nodes {
              id
              content {
                ... on Issue { title }
                ... on DraftIssue { title }
              }
            }
          }
        }
      }
    }
    """
    cursor: Optional[str] = None
    for _ in range(0, 20):
        data = _gh_graphql(query, {"projectId": project_id, "cursor": cursor})
        items = data["data"]["node"]["items"]
        nodes = items.get("nodes") or []
        for n in nodes:
            content = n.get("content")
            if content and content.get("title") == title:
                return n["id"]
        page = items.get("pageInfo") or {}
        if not page.get("hasNextPage"):
            break
        cursor = page.get("endCursor")
        if not cursor:
            break
    return None


def _create_draft_item(project_id: str, title: str) -> str:
    query = """
    mutation($projectId: ID!, $title: String!) {
      addProjectV2DraftIssue(input: { projectId: $projectId, title: $title }) {
        projectItem { id }
      }
    }
    """
    data = _gh_graphql(query, {"projectId": project_id, "title": title})
    return data["data"]["addProjectV2DraftIssue"]["projectItem"]["id"]


def _set_text_field(project_id: str, item_id: str, field_id: str, value: str) -> None:
    query = """
    mutation($projectId: ID!, $itemId: ID!, $fieldId: ID!, $value: String!) {
      updateProjectV2ItemFieldValue(
        input: {
          projectId: $projectId,
          itemId: $itemId,
          fieldId: $fieldId,
          value: { text: $value }
        }
      ) {
        clientMutationId
      }
    }
    """
    _gh_graphql(query, {"projectId": project_id, "itemId": item_id, "fieldId": field_id, "value": value})


def _set_number_field(project_id: str, item_id: str, field_id: str, value: float) -> None:
    query = """
    mutation($projectId: ID!, $itemId: ID!, $fieldId: ID!, $value: Float!) {
      updateProjectV2ItemFieldValue(
        input: {
          projectId: $projectId,
          itemId: $itemId,
          fieldId: $fieldId,
          value: { number: $value }
        }
      ) {
        clientMutationId
      }
    }
    """
    _gh_graphql(query, {"projectId": project_id, "itemId": item_id, "fieldId": field_id, "value": value})


def _get_single_select_options(org: str, project_number: int, field_name: str) -> Dict[str, str]:
    query = """
    query($org: String!, $number: Int!) {
      organization(login: $org) {
        projectV2(number: $number) {
          fields(first: 50) {
            nodes {
              ... on ProjectV2SingleSelectField {
                name
                options { id name }
              }
            }
          }
        }
      }
    }
    """
    data = _gh_graphql(query, {"org": org, "number": project_number})
    nodes = data["data"]["organization"]["projectV2"]["fields"]["nodes"]
    for n in nodes:
        if n and n.get("name") == field_name:
            return {opt["name"]: opt["id"] for opt in n.get("options") or []}
    return {}


def _get_single_select_options_detailed(
    org: str, project_number: int, field_name: str
) -> Optional[Dict[str, object]]:
    query = """
    query($org: String!, $number: Int!) {
      organization(login: $org) {
        projectV2(number: $number) {
          fields(first: 50) {
            nodes {
              ... on ProjectV2SingleSelectField {
                id
                name
                options { id name color description }
              }
            }
          }
        }
      }
    }
    """
    data = _gh_graphql(query, {"org": org, "number": project_number})
    nodes = data["data"]["organization"]["projectV2"]["fields"]["nodes"]
    for n in nodes:
        if n and n.get("name") == field_name:
            return n
    return None


def _ensure_single_select_option(
    org: str,
    project_number: int,
    field_name: Optional[str],
    option_name: str,
    *,
    color: str = "GRAY",
    description: str = "",
) -> bool:
    if not field_name:
        return False

    field = _get_single_select_options_detailed(org, project_number, field_name)
    if not field:
        return False

    options = field.get("options") or []
    if any((o or {}).get("name") == option_name for o in options):
        return True

    # updateProjectV2Field replaces the option list, so we must send the full list.
    new_options = []
    for o in options:
        if not o:
            continue
        new_options.append(
            {
                "name": o.get("name") or "",
                "color": o.get("color") or "GRAY",
                "description": o.get("description") or "",
            }
        )

    new_options.append({"name": option_name, "color": color, "description": description})

    # NOTE: This repo uses an older gh CLI; complex GraphQL input objects passed
    # as variables via `-F input={...}` are treated as strings and rejected.
    # Embed the input directly in the mutation instead.
    opt_literals: List[str] = []
    for o in new_options:
        opt_literals.append(
            "{" +
            f"name:{json.dumps(o['name'])}," +
            f"color:{o['color']}," +
            f"description:{json.dumps(o['description'])}" +
            "}"
        )

    mutation = (
        "mutation {"
        " updateProjectV2Field(input: {"
        f" fieldId: {json.dumps(field['id'])},"
        f" singleSelectOptions: [{','.join(opt_literals)}]"
        " }) {"
        "   projectV2Field {"
        "     ... on ProjectV2SingleSelectField { id name options { id name } }"
        "   }"
        " }"
        "}"
    )
    try:
        _gh_graphql(mutation, {})
        return True
    except Exception:
        return False


def _set_single_select(project_id: str, item_id: str, field_id: str, option_id: str) -> None:
    query = """
    mutation($projectId: ID!, $itemId: ID!, $fieldId: ID!, $optionId: String!) {
      updateProjectV2ItemFieldValue(
        input: {
          projectId: $projectId,
          itemId: $itemId,
          fieldId: $fieldId,
          value: { singleSelectOptionId: $optionId }
        }
      ) {
        clientMutationId
      }
    }
    """
    _gh_graphql(query, {"projectId": project_id, "itemId": item_id, "fieldId": field_id, "optionId": option_id})


def _pull_statuses(project_id: str, status_field_name: str, state: Dict[str, object]) -> None:
    # Pull Status from first 200 items and write into state by projectItemId.
    # We key by localId -> projectItemId mapping, then update status value.
    query = """
    query($projectId: ID!, $cursor: String) {
      node(id: $projectId) {
        ... on ProjectV2 {
          items(first: 100, after: $cursor) {
            pageInfo { hasNextPage endCursor }
            nodes {
              id
              fieldValues(first: 50) {
                nodes {
                  ... on ProjectV2ItemFieldSingleSelectValue {
                    field { ... on ProjectV2SingleSelectField { name } }
                    name
                  }
                  ... on ProjectV2ItemFieldTextValue {
                    field { ... on ProjectV2FieldCommon { name } }
                    text
                  }
                }
              }
            }
          }
        }
      }
    }
    """
    status_by_item: Dict[str, str] = {}
    cursor: Optional[str] = None
    for _ in range(0, 20):
        data = _gh_graphql(query, {"projectId": project_id, "cursor": cursor})
        items = data["data"]["node"]["items"]
        nodes = items.get("nodes") or []
        for it in nodes:
            fv = it.get("fieldValues", {}).get("nodes", [])
            for n in fv:
                field = n.get("field")
                if not field:
                    continue
                if field.get("name") == status_field_name:
                    # single-select uses name; text uses text
                    status_val = n.get("name") or n.get("text")
                    if status_val:
                        status_by_item[it["id"]] = status_val

        page = items.get("pageInfo") or {}
        if not page.get("hasNextPage"):
            break
        cursor = page.get("endCursor")
        if not cursor:
            break

    mapping = state.setdefault("mapping", {})
    for local_id, m in mapping.items():
        pid = m.get("projectItemId")
        if pid and pid in status_by_item:
            m["status"] = status_by_item[pid]


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: sync_project.py /abs/path/to/export.json /abs/path/to/github-project-sync-config.json", file=sys.stderr)
        return 2

    export_path = Path(sys.argv[1]).resolve()
    config_path = Path(sys.argv[2]).resolve()

    export_data = json.loads(export_path.read_text(encoding="utf-8"))
    cfg = json.loads(config_path.read_text(encoding="utf-8"))

    org = cfg["org"]
    project_number = int(cfg["projectNumber"])

    project_id, field_ids = _get_project(org, project_number)

    # Resolve fields
    status_field = cfg["fieldNames"].get("status")
    priority_field = cfg["fieldNames"].get("priority")
    effort_field = cfg["fieldNames"].get("effort")
    feasibility_field = cfg["fieldNames"].get("feasibility")
    safe_type_field = cfg["fieldNames"].get("safeType")
    complexity_field = cfg["fieldNames"].get("complexity")

    status_field_id = field_ids.get(status_field) if status_field else None
    priority_field_id = field_ids.get(priority_field) if priority_field else None
    effort_field_id = field_ids.get(effort_field) if effort_field else None
    feasibility_field_id = field_ids.get(feasibility_field) if feasibility_field else None

    # Ensure required new fields exist (best-effort). Default to TEXT fields.
    safe_type_field_id = _ensure_field(project_id, field_ids, safe_type_field)
    complexity_field_id = _ensure_field(project_id, field_ids, complexity_field)

    # Single select options (if the project uses single-select for these fields)
    status_options = _get_single_select_options(org, project_number, status_field) if status_field else {}

    # Ensure expected Priority options exist (so we don't accumulate unmapped values).
    # Current models emit P0..P3. Best-effort only (do not block sync).
    _ensure_single_select_option(org, project_number, priority_field, "P3", color="GRAY")

    priority_options = _get_single_select_options(org, project_number, priority_field) if priority_field else {}
    effort_options = _get_single_select_options(org, project_number, effort_field) if effort_field else {}
    feasibility_options = _get_single_select_options(org, project_number, feasibility_field) if feasibility_field else {}
    safe_type_options = _get_single_select_options(org, project_number, safe_type_field) if safe_type_field else {}
    complexity_options = _get_single_select_options(org, project_number, complexity_field) if complexity_field else {}

    state_path = config_path.parent / "github-project-sync-state.json"
    state = _load_json(state_path)
    mapping = state.setdefault("mapping", {})

    defaults = cfg.get("defaults", {})
    status_new = defaults.get("statusForNewItems")

    complexity_effort = cfg.get("mappings", {}).get("complexityToEffort", {})

    # Local export index for attachment chains & descriptions.
    items_list = export_data.get("items", [])
    by_local_id = {str(i.get("localId")): i for i in items_list if i.get("localId")}

    try:
        for item in items_list:
            local_id = item["localId"]
            title = item["title"]
            fields = item.get("fields") or {}

            # Ensure mapping entry exists early so we can record errors.
            m = mapping.get(local_id) or {}
            mapping[local_id] = {
                **m,
                "title": title,
                "kind": item.get("kind"),
                "source": item.get("source"),
            }

            try:
                project_item_id = mapping[local_id].get("projectItemId")

                if not project_item_id:
                    # Best effort search by title before creating
                    project_item_id = _find_item_by_title(project_id, title)

                created = False
                if not project_item_id:
                    project_item_id = _create_draft_item(project_id, title)
                    created = True

                mapping[local_id]["projectItemId"] = project_item_id
                mapping[local_id].pop("sync_error", None)

                # Set SAFe Type (Item SAFe Type)
                safe_val = _safe_type_for_kind(item.get("kind"))
                if safe_type_field_id and safe_val:
                    if safe_type_options:
                        if safe_val in safe_type_options:
                            _set_single_select(
                                project_id,
                                project_item_id,
                                safe_type_field_id,
                                safe_type_options[safe_val],
                            )
                        else:
                            mapping[local_id]["safeType_unmapped"] = str(safe_val)
                    else:
                        _set_text_field(project_id, project_item_id, safe_type_field_id, str(safe_val))

                # Set Complexity field (string as requested)
                comp_val = fields.get("complexity")
                if complexity_field_id and comp_val:
                    comp_str = str(comp_val)
                    if complexity_options:
                        if comp_str in complexity_options:
                            _set_single_select(
                                project_id,
                                project_item_id,
                                complexity_field_id,
                                complexity_options[comp_str],
                            )
                        else:
                            mapping[local_id]["complexity_unmapped"] = comp_str
                    else:
                        _set_text_field(project_id, project_item_id, complexity_field_id, comp_str)

                # Update description (Draft Issue body)
                desc = _build_description(item, by_local_id)
                content = _get_project_item_content(project_item_id)
                cid = content.get("contentId")
                body = content.get("body")
                if content.get("contentType") == "DraftIssue" and cid and isinstance(body, str):
                    if body.strip() != desc.strip():
                        _update_draft_issue(str(cid), str(title), desc)
                        mapping[local_id]["description_updated"] = True
                    else:
                        mapping[local_id]["description_updated"] = False
                    mapping[local_id]["draftIssueId"] = str(cid)

                # Set fields: Priority, Effort
                bucket = fields.get("priorityBucket")
                if bucket and priority_field_id:
                    mapped = cfg.get("mappings", {}).get("priorityBucketToValue", {}).get(bucket, str(bucket))
                    if priority_options:
                        if mapped in priority_options:
                            _set_single_select(
                                project_id,
                                project_item_id,
                                priority_field_id,
                                priority_options[mapped],
                            )
                        else:
                            mapping[local_id]["priority_unmapped"] = str(mapped)
                    else:
                        _set_text_field(project_id, project_item_id, priority_field_id, str(mapped))

                complexity = fields.get("complexity")
                if complexity and effort_field_id:
                    effort_val = complexity_effort.get(str(complexity).upper())
                    if effort_val is None:
                        effort_val = 5
                    if effort_options:
                        if float(effort_val).is_integer():
                            effort_key = str(int(effort_val))
                        else:
                            effort_key = str(effort_val)
                        if effort_key in effort_options:
                            _set_single_select(
                                project_id,
                                project_item_id,
                                effort_field_id,
                                effort_options[effort_key],
                            )
                        else:
                            mapping[local_id]["effort_unmapped"] = effort_key
                    else:
                        try:
                            _set_number_field(project_id, project_item_id, effort_field_id, effort_val)
                        except Exception:
                            _set_text_field(project_id, project_item_id, effort_field_id, str(effort_val))
                            mapping[local_id]["effort_written_as_text"] = True

                # Only set status for new items to avoid clobbering workflow
                if created and status_new and status_field_id:
                    if status_options:
                        if status_new in status_options:
                            _set_single_select(
                                project_id,
                                project_item_id,
                                status_field_id,
                                status_options[status_new],
                            )
                        else:
                            mapping[local_id]["status_unmapped"] = str(status_new)
                    else:
                        _set_text_field(project_id, project_item_id, status_field_id, status_new)

            except Exception as e:
                # Continue-on-error: record and move on.
                mapping[local_id]["sync_error"] = str(e).strip()[:1000]

            # Persist after each item to avoid duplicates on partial failures.
            _save_json(state_path, state)

    finally:
        _save_json(state_path, state)

    # Pull statuses back into state file
    if status_field:
        _pull_statuses(project_id, status_field, state)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
