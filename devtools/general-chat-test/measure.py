#!/usr/bin/env python3
"""The measurement with a real model (README.md) - after `setup.py base` and `setup.py models`.

Asks every question of questions.yml in the general chat - a new conversation each, as an Authelia person in
AI-Allgemein - through /tenant/chat/stream, and writes local-data/measurements/general-chat/<date>.md (ignored by git):
per question the topics asked against the expected ones, whether the answer holds what it should and never what it
must not (a document of an area the topic's key does not release), whether a passage was cited under the wrong topic
(ids or files mixed up), the time and the cost (the admin's usage before and after).

  python3 measure.py [--person bernd] [--only id1,id2]
"""
import argparse
import datetime
import json
import pathlib

import stack

HERE = pathlib.Path(__file__).parent
REPORT_DIR = HERE.parent.parent / "local-data" / "measurements" / "general-chat"


def questions():
    """The flat list of questions.yml - its own small format, no YAML library needed."""
    items, current = [], None
    for line in (HERE / "questions.yml").read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith("- "):
            current = {}
            items.append(current)
            line = line[2:]
        key, _, value = line.strip().partition(":")
        value = value.strip()
        if value.startswith("[") and value.endswith("]"):
            current[key] = [item.strip() for item in value[1:-1].split(",") if item.strip()]
        else:
            current[key] = value.strip('"')
    return items


def files_of_topics():
    """The documents' file names by the connection that knows them - to see a passage cited under the wrong topic."""
    state = json.loads(stack.STATE_FILE.read_text())
    return {topic["connection"]: {path.name for path in (HERE / "documents" / topic["folder"]).glob("*.md")}
            for topic in state["topics"]}


def mixed_up(answer, files, asked):
    """
    The documents the answer names under the wrong topic: a file of a topic that was not asked at all, or one whose
    citation - the text from the file name to the next closing bracket or line end - names another topic than its own.
    A rough check whatever form the model cites in; read the answers in the report for the rest.
    """
    found = []
    for topic, names in files.items():
        for name in names:
            start = answer.find(name)
            while start >= 0:
                end = min([i for i in (answer.find(")", start), answer.find("\n", start)) if i >= 0] or [len(answer)])
                citation = answer[start:end]
                others = [other for other in files if other != topic and other in citation]
                if topic not in asked:
                    found.append(f"{name} ({topic} not asked)")
                elif others:
                    found.append(f"{name} under {', '.join(others)}")
                start = answer.find(name, start + len(name))
    return sorted(set(found))


def total_cost(admin):
    usage = admin.app("GET", "/admin/usage")[2] or []
    return sum(float(row.get("amount") or 0) for row in usage)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--person", default="bernd")
    parser.add_argument("--only", default="")
    arguments = parser.parse_args()
    state = json.loads(stack.STATE_FILE.read_text())
    model_id = state.get("chatModelId")
    if not model_id:
        raise SystemExit("run setup.py models first")
    person = stack.Person(arguments.person)
    status, body = person.sign_in(state["general"]["tenantId"])
    if status != 200:
        raise SystemExit(f"{arguments.person} could not sign in at the general chat: {status} {body}")
    admin = stack.admin()
    files = files_of_topics()
    only = {item for item in arguments.only.split(",") if item}
    rows = []
    for question in questions():
        if only and question["id"] not in only:
            continue
        before = total_cost(admin)
        result = person.ask(model_id, question["question"])
        cost = total_cost(admin) - before
        asked = sorted({software for _, software in result["tools"] if software})
        expected = sorted(question.get("topics", []))
        answer = result.get("answer") or ""
        holds = not question.get("expect") or question["expect"].lower() in answer.lower()
        kept = not question.get("forbidden") or question["forbidden"].lower() not in answer.lower()
        mixed = mixed_up(answer, files, asked)
        right = set(expected) <= set(asked) if expected else not asked
        rows.append({"id": question["id"], "asked": asked, "expected": expected,
                     "right": right, "holds": holds, "kept": kept,
                     "mixed": mixed, "seconds": result["seconds"], "cost": cost,
                     "error": result.get("error"), "answer": answer})
        print(f"{question['id']}: asked {asked or '-'} (expected {expected or '-'}), "
              f"{'ok' if holds and kept else 'CHECK'}, {result['seconds']:.1f} s")
    write(rows, arguments.person)


def write(rows, person):
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    now = datetime.datetime.now()
    path = REPORT_DIR / f"{now:%Y-%m-%d-%H%M}.md"
    lines = [f"# General chat over topic tenants - measurement {now:%Y-%m-%d %H:%M}", "",
             f"Asked as {person} in the test stack (devtools/general-chat-test), one new conversation per question.", "",
             "| Question | Topics asked | Expected | Right topic | Answer holds | Areas kept | Mixed up | Seconds | Cost |",
             "|---|---|---|---|---|---|---|---|---|"]
    for row in rows:
        lines.append(f"| {row['id']} | {', '.join(row['asked']) or '-'} | {', '.join(row['expected']) or '-'} | "
                     f"{'yes' if row['right'] else 'NO'} | {'yes' if row['holds'] else 'NO'} | "
                     f"{'yes' if row['kept'] else 'NO'} | {'; '.join(row['mixed']) or '-'} | {row['seconds']:.1f} | "
                     f"{row['cost']:.4f} |")
    total = len(rows) or 1
    lines += ["", f"Right topic: {sum(r['right'] for r in rows)}/{len(rows)}, answer holds: "
                  f"{sum(r['holds'] for r in rows)}/{len(rows)}, areas kept: {sum(r['kept'] for r in rows)}/{len(rows)}, "
                  f"mixed up: {sum(bool(r['mixed']) for r in rows)}, average {sum(r['seconds'] for r in rows) / total:.1f} s, "
                  f"cost {sum(r['cost'] for r in rows):.4f}.", "", "## Answers", ""]
    for row in rows:
        lines += [f"### {row['id']}", "", row["error"] or row["answer"], ""]
    path.write_text("\n".join(lines))
    print(f"written: {path}")


if __name__ == "__main__":
    main()
