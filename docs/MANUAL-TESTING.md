# Testing Hive Mind by hand

A guided walk through everything the system does, in the order it does it, with what
to look for at each step. Roughly 25 minutes end to end, most of it waiting for a
local model.

The point is not to run the commands — it is to check the claims. Each section says
what would count as the system being wrong.

---

## 0 · Prerequisites

```bash
docker compose -f deploy/docker-compose.yml up -d --wait     # Neo4j, Postgres, Redis
```

LM Studio on `:1234` with **two** models loaded — a chat model and an embedding
model. Everything below works against any OpenAI-compatible endpoint; see
[Pointing at a different model](../README.md#pointing-at-a-different-model).

```bash
./gradlew :hive-app:bootRun --args='doctor'
```

Expect both lines `OK`. If `llm` shows a 5-second timeout while `curl
localhost:1234/v1/models` works, that is the HTTP/2 problem described in
`LlmProbe` — the fix is already in, so seeing it again means something regressed.

`doctor` exits non-zero when a dependency is unusable, so Gradle prints
`BUILD FAILED`. That is the check reporting, not the build breaking.

---

## 1 · The corpus is real content, not lorem ipsum

```bash
ls corpus/meridian-media/{slack/threads,notion,zoom}
cat corpus/meridian-media/README.md
```

26 episodes across three wire formats — Slack `conversations.replies` JSON, Notion
markdown with frontmatter, Zoom WebVTT. Read
`corpus/meridian-media/zoom/content-sync-2026-02-11.vtt`: the speakers are
`Speaker 1/2/3`, unnamed, which is what Zoom actually emits when it cannot match a
voice to an account. Resolving them is step 3.

The answer key lives in `corpus/meridian-media/ground-truth/` and **the pipeline
never reads it**. To regenerate the corpus:

```bash
python3 scripts/build_corpus.py
```

It fails loudly if the key cites content that no longer exists.

---

## 2 · Extraction, and whether to trust it

```bash
./gradlew :hive-app:bootRun --args='extract --limit=3'    # ~30s, for a quick look
./gradlew :hive-app:bootRun --args='extract'              # ~8 min, the full pass
```

Watch the `kept` / `dropped` columns. Dropped facts are ones whose quotes could not
be found in the source — that is the grounding gate working, and a run with **zero**
drops is more suspicious than one with a few.

Then look at what it actually produced:

```bash
python3 - <<'EOF'
import json, glob
run = json.load(open(sorted(glob.glob('eval-runs/*/extraction.json'))[-1]))
for r in run[:3]:
    for a in r['accepted'][:2]:
        print(f"{a['type']:12} {a['statement']}")
        print(f"             quote: \"{a['evidence'][0]['verbatimSpan'][:70]}\"")
EOF
```

**What to check:** open the cited episode and confirm the quote is really there,
word for word. That is the whole claim of the grounding gate. A paraphrase that got
through would be a genuine bug.

---

## 3 · Scoring extraction without re-running the model

```bash
./gradlew :hive-app:bootRun --args='score'
```

Two recall numbers. **Content recall** is whether the fact was found at all;
**strict recall** additionally requires the right type. The gap between them is
type confusion, currently ~30 points — the extractor reads the conversations well
and classifies them badly.

Precision is deliberately absent. The key lists what *must* be found, not everything
the corpus contains, so the ~95 unlisted facts are mostly true. The honest precision
signal is the fabricated-quote rate on the same output.

---

## 4 · Identity resolution and the graph

```bash
./gradlew :hive-app:bootRun --args='load --fresh'
```

**Check the speaker block.** It should resolve the three anonymous Zoom labels:

```
speakers by method: {HANDLE=53, LLM_ADJUDICATED=3}
  Speaker 2 -> Alexandra Petrova   Speaker 1 -> Dana Okonkwo   Speaker 3 -> Priya Raghunathan
```

Ground truth is in `ground-truth/entities.json`. Any other assignment is wrong — and
it matters, because the original launch date was decided in that room and only
recapped afterwards.

**Check `deterministic share`.** It should be ~91%. Most identity work is done by
joining what the source systems already told us; a model is asked only about
genuinely anonymous speakers. If that number falls, the resolver has started
guessing where it could have looked something up.

### Poke the graph directly

Neo4j browser at <http://localhost:7474> — `neo4j` / `hivemind-dev`.

```cypher
// The three-link chain, with the interval each date was valid for
MATCH path=(newest:Fact)-[:SUPERSEDES*]->(oldest:Fact)
WHERE newest.statement CONTAINS 'Frontier' AND NOT ()-[:SUPERSEDES]->(newest)
RETURN [f IN nodes(path) | f.statement + ' [' + f.status + ' → ' +
        coalesce(toString(date(f.validTo)), 'now') + ']'] AS chain
ORDER BY length(path) DESC LIMIT 1;

// The two Alexes must stay two people
MATCH (p:Person) WHERE p.name STARTS WITH 'Alex'
OPTIONAL MATCH (f:Fact)-[:OWNED_BY]->(p)
RETURN p.name, p.email, count(f) AS owns;

// ACL: derived facts inherit the grants of their sources
MATCH (f:Fact) RETURN f.visibility, size(f.aclGrants) AS grantsNeeded, count(*)
ORDER BY f.visibility;
```

---

## 5 · Asking it things

```bash
./gradlew :hive-app:bootRun --args='ask When does Frontier premiere'
```

Expect **June 1, 2026** with a citation, followed by the history. If it opens with
May 4 or June 15, the graph has a stale fact marked current.

```bash
# As-of: the same question answered at a past date
./gradlew :hive-app:bootRun --args='ask --as-of=2026-04-15 What is the Frontier launch date'
```

Expect **June 15** — what was true that day, not what is true now.

```bash
# The identity decoy: no card's sentence names an owner
./gradlew :hive-app:bootRun --args='ask Who owns the video CMS migration'
```

Expect **Alex Chen**. Alexandra Petrova would mean the resolver merged them.

```bash
# See what the model was actually given
./gradlew :hive-app:bootRun --args='ask --cards When does Frontier premiere'
```

This is the most useful debugging flag in the system. A wrong answer is nearly
always a wrong card — check whether the facts are right and correctly marked
`CURRENT` / `NO LONGER TRUE` before blaming the prompt.

### The access-control demonstration

Ask the same question as two readers:

```bash
./gradlew :hive-app:bootRun --args='ask Is there a hiring freeze'
./gradlew :hive-app:bootRun --args='ask --grants=slack:C_EXEC,zoom:M_EXEC_OFFSITE,notion:P_BOARD_Q1 Is there a hiring freeze'
```

The second answers fully. The first **should** decline — and currently does not; it
says "there is no hiring freeze", which is a confident denial of something true and
merely invisible to that reader. This is the known limitation described in the
README. Add `--cards` to both and confirm the restricted facts genuinely never reach
the unprivileged reader: access control is correct where it is *enforced*, and the
failure is the model reasoning past a filtered context.

---

## 6 · Scoring the answers

```bash
./gradlew :hive-app:bootRun --args='evaluate'
```

Expect **8/10 pass · 1 forbidden · 1 missed**. Exits non-zero on a leak, so it works
as a CI gate.

The two failures are documented rather than hidden: `Q5` is the ACL case above, `Q4`
is a false supersession that closed the fact answering it. If a *different* question
starts failing, something regressed — that is what this command is for.

---

## 6.5 · The MCP server

Needs no client — the protocol is line-delimited JSON-RPC on stdin and stdout, so a
here-doc is a complete session. Build the jar first, because Gradle writes to stdout
and stdout belongs to the protocol:

```bash
./gradlew :hive-app:bootJar

{ printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"trace_decision","arguments":{"query":"the Frontier launch date"}}}' \
  ; sleep 4; } \
| java -jar hive-app/build/libs/hive-app-0.1.0-SNAPSHOT.jar mcp 2>/dev/null
```

Expect four tools listed, then the three-version Frontier chain — May 4 → June 15 →
June 1, oldest first, with only the last marked `CURRENT`.

The `sleep` is not padding. Closing stdin is how an MCP client says *shut down*, so
without it the server exits the instant `printf` finishes and the replies never get
written. Holding the pipe open is what a real client does for the length of a session.

**Check that stdout stayed clean.** Drop the `2>/dev/null` and every log line should
appear on stderr, with nothing but JSON-RPC on stdout. A stray line on stdout is the
one failure mode that breaks every client and names no culprit.

**The access-control demonstration, again.** Run the same `search_knowledge` call for
`Is there a hiring freeze?` with and without `--grants=slack:C_EXEC,zoom:M_EXEC_OFFSITE,notion:P_BOARD_Q1`.
Without them the result should carry `At least 7 further facts match this question and
are outside this reader's access`; with them, the hiring-freeze facts themselves and no
such line. Confirm too that no tool in `tools/list` accepts a `grants` argument — the
reader is fixed at launch, and a model that could name its own grants would.

Over HTTP instead, with the server running:

```bash
curl -sD - -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -X POST http://localhost:8080/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

Take the `Mcp-Session-Id` header from the response and send it back on every
subsequent request. Replies arrive as SSE, so the JSON is on a `data:` line.

---

## 7 · Browsing it

```bash
./gradlew :hive-app:bootRun --args='export'
```

Open `vault/` in Obsidian and turn on the graph view. Start at
`Meridian Media.md`, follow a decision that changed, and click back through
`replaced` links. Superseded notes are still there and marked with a warning
callout — the history is walkable rather than merely queryable.

---

## Where to look when something is wrong

| Symptom | Look at |
|---|---|
| Answer is confidently out of date | `ask --cards` — is a stale fact marked `CURRENT`? |
| Answer declines when the fact exists | Same — the fact is probably marked superseded by mistake |
| A person owns work that is not theirs | `load` output, `deterministic share`, then `entities.json` |
| Extraction finds nothing in an episode | The episode may genuinely establish nothing; check `dropped` |
| An MCP client connects and immediately drops | Something wrote to stdout — run the session without `2>/dev/null` and look for a non-JSON line |
| An MCP tool returns nothing for a reader | Check the withheld count in the result; the facts may exist and be closed to them |
| Everything times out, curl works | HTTP/2 versus a local model server — see `LlmProbe` |
