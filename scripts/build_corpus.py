#!/usr/bin/env python3
"""Emit the Meridian Media sample corpus.

Content is hand-authored in ``scripts/corpus/``; this script only renders it into
the wire formats the real connectors will read — Slack ``conversations.replies``
JSON, Notion markdown with frontmatter, Zoom WebVTT plus recording metadata. Writing
the same shapes now means the filesystem connector and the live connectors in M2
share a parser instead of diverging.

Timestamps are derived rather than typed by hand, so the three sources agree about
when things happened. That matters more than it sounds: half the corpus's traps are
temporal, and a corpus whose clocks disagree tests nothing.

    python3 scripts/build_corpus.py
"""

import json
import shutil
from datetime import timedelta
from pathlib import Path

from corpus import BY_CHANNEL_ID, BY_HANDLE, CHANNELS, PEOPLE, at, permalink, plus, slack_ts
from corpus.ground_truth import ENTITIES, FACTS, NON_MATCHES, QUESTIONS
from corpus.notion_pages import PAGES
from corpus.slack_threads import THREADS
from corpus.zoom_meetings import MEETINGS

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "corpus" / "meridian-media"


def write_json(path: Path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def build_slack() -> dict:
    write_json(
        OUT / "slack" / "users.json",
        [
            {
                "id": person["slack_id"],
                "name": person["handle"],
                "real_name": person["real_name"],
                "profile": {
                    "display_name": person["display_name"],
                    "email": person["email"],
                    "title": person["title"],
                },
            }
            for person in PEOPLE
        ],
    )
    write_json(
        OUT / "slack" / "channels.json",
        [
            {
                "id": channel["id"],
                "name": channel["name"],
                "is_private": channel["visibility"] != "PUBLIC",
                "visibility": channel["visibility"],
            }
            for channel in CHANNELS
        ],
    )

    episode_ids = {}
    for thread in THREADS:
        channel = BY_CHANNEL_ID[thread["channel"]]
        started = at(thread["started"])
        root_ts = slack_ts(started, 100)
        # Must match SourceRef.key() in SlackThreadReader, or the gold set cites
        # episodes the pipeline never produces and every score reads zero.
        episode_ids[f"slack:{thread['key']}"] = f"slack:{channel['id']}/{root_ts}"

        messages = []
        for index, (handle, offset, text) in enumerate(thread["messages"]):
            moment = plus(started, offset)
            ts = slack_ts(moment, 100 + index)
            messages.append(
                {
                    "type": "message",
                    "ts": ts,
                    "thread_ts": root_ts,
                    "user": BY_HANDLE[handle]["slack_id"],
                    "text": text,
                    "permalink": permalink(channel["id"], ts),
                }
            )

        write_json(
            OUT / "slack" / "threads" / f"{thread['key']}.json",
            {
                "channel": {
                    "id": channel["id"],
                    "name": channel["name"],
                    "visibility": channel["visibility"],
                },
                "thread_ts": root_ts,
                "messages": messages,
            },
        )
    return episode_ids


def vtt_timestamp(seconds: float) -> str:
    hours, rest = divmod(int(seconds), 3600)
    minutes, secs = divmod(rest, 60)
    millis = int(round((seconds - int(seconds)) * 1000))
    return f"{hours:02d}:{minutes:02d}:{secs:02d}.{millis:03d}"


def build_zoom() -> dict:
    episode_ids = {}
    for meeting in MEETINGS:
        episode_ids[f"zoom:{meeting['key']}"] = f"zoom:{meeting['uuid']}"
        cues = []
        for index, (speaker, start, duration, text) in enumerate(meeting["turns"], start=1):
            cues.append(
                f"{index}\n{vtt_timestamp(start)} --> {vtt_timestamp(start + duration)}\n{speaker}: {text}\n"
            )
        vtt = "WEBVTT\n\n" + "\n".join(cues)

        path = OUT / "zoom" / f"{meeting['key']}.vtt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(vtt, encoding="utf-8")

        last_speaker, last_start, last_duration, _ = meeting["turns"][-1]
        started = at(meeting["started"])
        write_json(
            OUT / "zoom" / f"{meeting['key']}.meta.json",
            {
                "uuid": meeting["uuid"],
                "topic": meeting["topic"],
                "start_time": meeting["started"],
                "end_time": (started + timedelta(seconds=last_start + last_duration + 30))
                .isoformat()
                .replace("+00:00", "Z"),
                "scope": {"id": meeting["scope_id"], "visibility": meeting["visibility"]},
                "participants": meeting["participants"],
                "recording_files": [{"file_type": "TRANSCRIPT", "file_name": f"{meeting['key']}.vtt"}],
            },
        )
    return episode_ids


def build_notion() -> dict:
    episode_ids = {}
    for page in PAGES:
        # Notion page ids are already stable, so key and episode id coincide.
        episode_ids[f"notion:{page['id']}"] = f"notion:{page['id']}"
        frontmatter = "\n".join(
            [
                "---",
                f"id: {page['id']}",
                f'title: "{page["title"]}"',
                f"created_time: {page['created_time']}",
                f"last_edited_time: {page['last_edited_time']}",
                f'created_by: "{page["created_by"]}"',
                f'last_edited_by: "{page["last_edited_by"]}"',
                f"visibility: {page['visibility']}",
                f"url: https://www.notion.so/meridian/{page['id']}",
                "---",
            ]
        )
        path = OUT / "notion" / f"{page['key']}.md"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"{frontmatter}\n\n# {page['title']}\n\n{page['body']}\n", encoding="utf-8")
    return episode_ids


def build_ground_truth(episode_ids: dict) -> None:
    """Writes the answer key with sources resolved to the ids the pipeline emits.

    Facts are authored against readable keys like ``slack:frontier-date-locked``
    because nobody can hand-write a Slack thread timestamp. The scorer, though,
    compares against what extraction actually produced, so both are emitted: the
    resolved id to match on, and the key to read.
    """
    resolved = []
    for fact in FACTS:
        resolved.append(
            {
                **fact,
                "sources": [episode_ids[source] for source in fact["sources"]],
                "source_keys": fact["sources"],
            }
        )
    write_json(OUT / "ground-truth" / "entities.json", {"entities": ENTITIES, "non_matches": NON_MATCHES})
    write_json(OUT / "ground-truth" / "facts.json", {"facts": resolved})
    write_json(OUT / "ground-truth" / "questions.json", {"questions": QUESTIONS})


def build_readme(counts: dict) -> None:
    traps = "\n".join(
        f"| {question['id']} | {question['question']} | {question['trap']} |" for question in QUESTIONS
    )
    readme = f"""# Meridian Media — sample corpus

A fictional mid-size digital media company: documentary series, podcasts, a
newsletter, about forty people. Roughly six months of Slack, Notion and Zoom, from
February to July 2026.

Generated by `scripts/build_corpus.py` from hand-authored content in
`scripts/corpus/`. Do not edit these files directly — edit the source and rebuild.

## Contents

| Source | Count | Format |
| --- | --- | --- |
| Slack threads | {counts['slack']} | `conversations.replies` JSON |
| Notion pages | {counts['notion']} | markdown with frontmatter |
| Zoom recordings | {counts['zoom']} | WebVTT plus recording metadata |

## Why it is hand-authored

This corpus is also the eval gold set. Every storyline plants a specific failure
mode, and `ground-truth/` states exactly what should come out — which means a
generated corpus would not do: you cannot score extraction against content whose
correct answers you never decided.

Small and dense beats large and vague. Twenty-odd episodes where every one carries a
trap produce sharper numbers than a thousand where most are filler.

## The traps

| Question | Asked | Tests |
| --- | --- | --- |
{traps}

## Ground truth

`ground-truth/` is never read by the pipeline.

- `entities.json` — canonical people and every alias they appear under, plus
  `non_matches`: pairs that must **not** be merged. A resolver that collapses
  everything scores perfectly on recall and ruins the graph, so the negative cases
  carry as much weight as the positive ones.
- `facts.json` — the facts that should be extracted, with supersession chains and
  the access each one requires.
- `questions.json` — eval queries with expected answers, forbidden phrases, and the
  grants the asker holds. Q5 and Q6 are the same question asked by different
  readers, and must produce different answers.

## Access control

`#exec`, the April exec offsite recording, and the Q1 board update are restricted.
The hiring freeze appears **only** in those three places; the one public trace is
Priya declining to explain why a role is paused. Any answer that reconstructs the
freeze for a reader without those grants is a leak, not a retrieval win.
"""
    (OUT / "README.md").write_text(readme, encoding="utf-8")


def validate(available: set) -> list:
    """Check the answer key still points at content that exists.

    The corpus is the gold set, so a fact citing a thread someone renamed is worse
    than no gold set at all — it silently scores a correct pipeline as wrong. Cheap
    to check here, expensive to discover during an eval run.
    """
    problems = []
    fact_ids = {fact["id"] for fact in FACTS}
    for fact in FACTS:
        for source in fact["sources"]:
            if source not in available:
                problems.append(f"fact {fact['id']} cites missing source {source}")
        for link in ("supersedes", "superseded_by"):
            target = fact.get(link)
            if target and target not in fact_ids:
                problems.append(f"fact {fact['id']} {link} unknown fact {target}")

    canonical = {entity["canonical"] for entity in ENTITIES}
    for non_match in NON_MATCHES:
        for name in non_match["pair"]:
            if name not in canonical:
                problems.append(f"non_match names unknown entity {name}")

    for question in QUESTIONS:
        for expected in question["expected_facts"]:
            if expected not in fact_ids:
                problems.append(f"question {question['id']} expects unknown fact {expected}")
    return problems


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    episode_ids = {}
    counts = {}
    for source, builder in (("slack", build_slack), ("zoom", build_zoom), ("notion", build_notion)):
        produced = builder()
        episode_ids.update(produced)
        counts[source] = len(produced)

    problems = validate(set(episode_ids))
    if problems:
        for problem in problems:
            print(f"  INVALID: {problem}")
        raise SystemExit(f"ground truth does not match the corpus ({len(problems)} problems)")

    build_ground_truth(episode_ids)
    build_readme(counts)

    episodes = counts["slack"] + counts["zoom"] + counts["notion"]
    print(f"Corpus written to {OUT.relative_to(ROOT)}")
    print(
        f"  {counts['slack']} Slack threads, {counts['notion']} Notion pages, "
        f"{counts['zoom']} Zoom recordings  ->  {episodes} episodes"
    )
    print(f"  ground truth: {len(ENTITIES)} entities, {len(FACTS)} facts, {len(QUESTIONS)} eval questions")


if __name__ == "__main__":
    main()
