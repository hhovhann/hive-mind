# Hive Mind

**Shared agentic knowledge infrastructure — Graph RAG over the conversations a team already had.**

Years of Slack threads, meeting recordings, and Notion pages hold every decision an
organisation ever made. Asking "why did we move the launch to April, and who owns
the follow-up?" currently means someone scrolling. Hive Mind turns that scrollback
into a temporal knowledge graph you can query, with a citation behind every claim.

> Status: **M1 complete.** Ingestion, extraction, identity resolution, the
> bi-temporal graph, hybrid retrieval and the eval harness are built, tested and
> measured. `extract`, `score`, `load`, `ask`, `evaluate` and `export` run end to end
> over a bundled corpus with no credentials. 8 of 10 gold questions are answered
> correctly; both failures are documented below rather than tuned around.
> See [Roadmap](#roadmap).

---

## Why not just chunk-and-embed?

That was tried. It fails on conversational corpora in five specific ways, and none
of them are fixed by a better embedding model or a longer context window:

| Failure | What actually breaks |
|---|---|
| **Temporal flattening** | A decision made in March, revised in May, reversed in July returns as three equally-ranked chunks. The model picks one. Nobody can tell which. |
| **Identity fragmentation** | `@alex` in Slack, "Alexandra Petrova" in Notion, and `Speaker 2` in a transcript become three unrelated nodes, so cross-source context never forms. |
| **Structural flattening** | A 500-character window cuts through a reply chain. "Let's go with the second option" stops referring to anything; "+1" stops agreeing with anyone. |
| **Anaphora across sources** | "as we discussed yesterday", "the doc Sarah shared", "that approach" — unresolvable from an isolated chunk. |
| **Authority collapse** | A CTO's ruling and an intern's musing embed identically. Brainstorms get reported as policy. |

Hive Mind's answer to all five is the same: **stop making the chunk the unit of
meaning.**

## How it works

**1 · The episode is the unit of extraction.**
A whole Slack thread, a whole meeting, one revision of a Notion page. Extraction
reads complete discourse, so pronouns still have referents and `+1` still has
something to agree with. Chunking still happens — afterwards, for the vector index,
once meaning has been captured.

**2 · A versioned ontology constrains extraction.**
`Decision`, `ActionItem`, `Commitment`, `Risk`, `OpenQuestion` — five types, each
declaring its required fields. That declaration compiles to the JSON Schema the
model is forced to fill, and doubles as the validation rule on write. Small on
purpose: precision falls faster than coverage rises as a taxonomy grows.

**3 · Every fact must quote its source, and the quote is checked.**
Extraction returns a verbatim span; Hive Mind string-matches it back against the
utterance it claims to come from. No match, no node. A model cannot quote a
sentence nobody wrote, and a deterministic comparison is cheaper, faster, and
harder to argue with than a second model grading the first. A span that is real but
attributed to the wrong line is repaired and warned about; a span that appears
nowhere means the fact was invented, and it is dropped.

**4 · Facts are bi-temporal, and nothing is overwritten.**
Each carries when it was true (`validFrom`/`validTo`) and when we learned it
(`ingestedAt`). A superseding decision closes the previous one and links to it with
`SUPERSEDES`. Current state is `validTo IS NULL`; history is the chain behind it.
"What did we decide in March" and "what holds now" become the same query with a
different date.

**5 · Access control is a query pre-filter, never a post-filter.**
Every source carries the containers it lives in. A derived fact inherits the
**union of its sources' requirements**, which is the intersection of who may read
it — a decision extracted half from `#general` and half from a private exec channel
is readable only by someone who can read both. Filtering after retrieval silently
shrinks top-k and turns a permissions bug into a quality bug.

**6 · Retrieval is four stages, not top-k.**
Seed (vector kNN + full-text + entity match, fused with RRF) → expand (typed graph
hops, time-decayed, pulling in supersession chains and owners) → rerank with source
diversity → assemble a **structured context pack**. The model receives a decision
card — statement, date, owner, status, what superseded it, supporting quotes with
permalinks — not a blob of concatenated text. Giving it structure instead of soup
is the actual anti-flattening move.

Stack: Java 25 LTS · Spring Boot 4.1 · LangChain4j 1.18 · Neo4j 5.26 · Gradle 9.6.
A step-by-step walkthrough for verifying all of it by hand is in
[docs/MANUAL-TESTING.md](docs/MANUAL-TESTING.md).

## Quick start

```bash
# 1. Infrastructure — Neo4j (graph + vectors), Postgres (operational), Redis (cache)
docker compose -f deploy/docker-compose.yml up -d --wait

# 2. A local model, via LM Studio on :1234 — or point HIVE_LLM_BASE_URL anywhere
#    OpenAI-compatible. Extraction wants a chat model and an embedding model loaded.

# 3. Check what Hive Mind can reach
./gradlew :hive-app:bootRun --args='doctor'

# 4. Extract facts from the bundled corpus — no credentials needed
./gradlew :hive-app:bootRun --args='extract'
./gradlew :hive-app:bootRun --args='extract --limit=3'   # while iterating on a prompt

# 5. Score the last run against the answer key, without calling the model again
./gradlew :hive-app:bootRun --args='score'

# 6. Resolve identities and load it into Neo4j
./gradlew :hive-app:bootRun --args='load --fresh'

# 7. Grade the answers against the key, and write a browsable vault
./gradlew :hive-app:bootRun --args='evaluate'
./gradlew :hive-app:bootRun --args='export'

# 8. Run it as a server — REST on :8080, MCP at POST /mcp
./gradlew :hive-app:bootRun

# 9. Or as an MCP server on stdio, for an agent to call
./gradlew :hive-app:bootRun --args='mcp'
```

`doctor` exits non-zero when a dependency is unusable, so it works as a CI gate.
Under Gradle a non-zero exit surfaces as `BUILD FAILED` — that is the check
reporting, not the build breaking.

Neo4j browser: <http://localhost:7474> (`neo4j` / `hivemind-dev`).

### Pointing at a different model

Everything funnels through one config block, so switching providers is not a
search-and-replace:

```bash
export HIVE_LLM_BASE_URL=https://api.openai.com/v1
export HIVE_LLM_API_KEY=sk-...
export HIVE_LLM_CHAT_MODEL=gpt-4o-mini
export HIVE_LLM_EMBEDDING_MODEL=text-embedding-3-small
```

## Where extraction actually stands

Full pass over the 26-episode corpus, Llama 3.1 8B running locally in LM Studio,
one schema-constrained call per episode:

| | |
|---|---|
| Episodes | 26 |
| Facts kept | 105 |
| Facts rejected by the grounding gate | 5 |
| **Fabricated quotes** | **3 of 110 proposed (2.7%)** |
| **Content recall** (fact found at all) | **10/10 (100%)** |
| **Strict recall** (found *and* typed correctly) | **7/10 (70%)** |
| Wall clock | 8 min |
| Tokens | 27.8k in, 10.9k out |

Reproduce with `extract` then `score`. Both numbers matter, and the gap between
them is the finding: **the extractor reads the conversations correctly and
classifies them badly.** Every planted fact was found, including the ones that only
exist in a recording and the ones split across three sources. Three were then
labelled with the wrong type:

| Gold | Wanted | Got |
|---|---|---|
| F6 | `OPEN_QUESTION` | `RISK` |
| F7 | `ACTION_ITEM` | `DECISION` |
| F9 | `DECISION` | `COMMITMENT` |

The type distribution says the same thing — 34% `RISK` and 25% `COMMITMENT`
against 5% `ACTION_ITEM` is not what a corpus of workplace conversations looks
like. `RISK` and `COMMITMENT` have the loosest definitions in the prompt, so the
model falls back to them when unsure. That is a prompt and ontology-boundary
problem, and it is fixed by sharpening type definitions, not by retrieving harder.

Two measurement notes, because the numbers are only worth as much as the method:

- **Matching is by embedding similarity, not word overlap.** The first scorer used
  token Jaccard and marked "The newsletter will continue to be published" as a miss
  against "The newsletter is retained; the question is closed until 2027 planning" —
  the same fact, sharing one content word. A benchmark that punishes correct
  paraphrase measures vocabulary, and would have sent tuning effort at a problem
  that did not exist.
- **Precision is deliberately not reported against the gold set.** The key lists what
  *must* be found, not everything the corpus contains, so the extra 95 facts are
  mostly true and simply unlisted. The fabrication rate above is the real precision
  signal: it is computed against the source text, not against anyone's opinion of
  what mattered.

## The graph, and what it can answer

`load` resolves identities and writes the extraction run into Neo4j. Over the sample
corpus: 9 people, 26 episodes, 115 utterances, 105 facts, 105 evidence edges.

**Identity resolution is 91% deterministic.** Slack ids, Notion authors and named
Zoom participants all join through the directory without a model. Only genuinely
anonymous speakers reach the adjudicator — 3 labels in one recording, resolved
correctly by matching what each speaker says against their job title:

```
speakers by method: {HANDLE=53, LLM_ADJUDICATED=3}
  Speaker 2  -> Alexandra Petrova     Speaker 1 -> Dana Okonkwo     Speaker 3 -> Priya Raghunathan
```

That matters more than it looks: the original Frontier launch date was decided in
that room and only recapped afterwards, so an unresolved speaker means knowing a
decision was made and not who made it.

**The supersession chain forms correctly**, with bi-temporal intervals:

```
The Frontier launch moves to June 1, 2026    AGREED      valid 2026-05-06 -> now
  supersedes  ... moves to June 15, 2026     SUPERSEDED  valid 2026-04-08 -> 2026-05-06
  supersedes  ... date is May 4, 2026        SUPERSEDED  valid 2026-02-11 -> 2026-04-08
```

So "when does Frontier premiere" and "what was the date in mid-April" are the same
query with a different date, and both are right:

```cypher
MATCH (f:Fact) WHERE f.statement CONTAINS 'Frontier launch'
  AND f.validFrom <= datetime($asOf)
  AND (f.validTo IS NULL OR f.validTo > datetime($asOf))
RETURN f.statement
```

The other planted traps hold too — `Alex Chen` (11 facts) and `Alexandra Petrova`
(26) stay separate people with distinct emails, and 18 of 105 facts carry an
inherited grant requirement while 87 are public.

### Two findings worth keeping

**Similarity is a candidate generator, not a judge.** Acting on cosine distance alone
produced 24 supersessions, roughly half of them wrong — including "CMS migrated by
end of Q2" supposedly replacing "migration runs in four phases". Two facts about one
partnership sit at 0.89 whether one revises the other, restates it from a second
source, or is simply also about that partner. Similarity now proposes and a
constrained adjudicator decides between `SUPERSEDES`, `DUPLICATE` and `UNRELATED` —
the same blocking-then-adjudication shape as entity resolution. A false supersession
is the expensive error: it removes a live fact from every current-state query and
surfaces nowhere as a failure.

**An escape hatch placed last dominates a small model.** The speaker adjudicator
returned `person: ""` for every label while an identical prompt tested by hand
answered all three correctly. The difference was one closing sentence — *"if a label
genuinely cannot be identified, leave its person empty"*. The hatch has to exist,
since a wrong owner is worse than a missing one, but on an 8B model the final
instruction is the one that wins. Moving it into the middle of the rules and ending
on the action recovered all three.

## Asking it things

```bash
./gradlew :hive-app:bootRun --args='ask When does Frontier premiere'
./gradlew :hive-app:bootRun --args='ask --as-of=2026-04-15 What is the Frontier launch date'
./gradlew :hive-app:bootRun --args='ask --grants=slack:C_EXEC Is there a hiring freeze'
./gradlew :hive-app:bootRun --args='ask --cards Who owns the video CMS migration'
```

Three of the planted traps, answered from the graph by a local 8B model:

```
Q: When does Frontier premiere
A: June 1, 2026 [5].
   It was previously set for May 4, 2026 [1], but Alexandra Petrova moved it to
   June 15 on April 8 [3]. Then the date changed again to June 1 on May 6 [5].

Q: Who owns the video CMS migration
A: Alex Chen [1].

Q: Is there a hiring freeze          (asked holding the exec grants)
A: There is a hiring freeze [4] ... in place through the end of Q3 [3].
```

The first is the temporal trap: three decided dates, all similar to the question,
and it gives the current one *and* the history. The second is the identity decoy —
no card's sentence names an owner, so the answer comes from the resolved
`OWNED_BY` edge, and it is Alex Chen rather than Alexandra Petrova.

### Scored against the answer key

`evaluate` asks all ten gold questions through the real retrieval path and grades
the answers — on target, cited, and free of anything the key forbids. It exits
non-zero on a leak, so it works as a CI gate rather than something someone reads.

```
Q1   PASS        When does Frontier premiere?                8 cards, 3 cited
Q2   PASS        What was the Frontier launch date as of...  8 cards, 3 cited
Q3   PASS        How many times did the launch date chan...  8 cards, 3 cited
Q4   MISSED      Are we discontinuing the newsletter?        declined, but the answer was there
Q5   FORBIDDEN   Is there a hiring freeze?                   said "hiring freeze"
Q6   PASS        Is there a hiring freeze?  (exec grants)    8 cards, 3 cited
Q7   PASS        Did we agree a revenue split with Nordwind? 8 cards, 5 cited
Q8   PASS        Who owns the video CMS migration?           8 cards, 2 cited
Q9   PASS        What did Priya commit to for the studio?    8 cards, 3 cited
Q10  PASS        Who set the original Frontier premiere?     8 cards, 2 cited

8/10 pass · 1 forbidden · 1 missed · 0 uncited
```

Verdicts are kept separate rather than averaged into one accuracy figure, because
the failures are not interchangeable: a **missed** answer costs someone a search, a
**forbidden** one hands them something they may not have. A single number improves
when you make the system leakier, which is the opposite of useful.

Grading uses two signals and either suffices — embedding similarity for correct
paraphrase, and coverage of the expected answer's dates, numbers and names.
The second exists because *"June 1"* and *"June 15"* are nearly identical to an
embedding and are the entire difference between right and wrong on half these
questions.

The harness earned itself immediately. Two of its first four failures were faults in
the key rather than the system: `must_not_say` conflated "must not lead with" with
"must not reveal", so a correct answer was marked wrong for explaining its own
history. And it caught a regression I had just introduced — an ACL prompt fix that
made two unrelated questions start refusing. Neither was visible from spot-checking
by hand.

### Where it still falls down

Asked **without** the exec grants, "is there a hiring freeze" should decline. The
restricted facts are correctly never retrieved — access control works where it is
enforced. But the model then reasons from the one public fragment it can see (a
role is "on pause") and asserts a conclusion anyway. It first inferred *"There is a
hiring freeze"*, which is a leak by inference; after tightening the prompt it flipped
to *"There is no hiring freeze"*, which is worse in a different way — a confident
denial of something that is true and merely invisible to that reader.

Three prompt revisions did not fix it, including a worked refusal example. The
honest conclusion is that **enforcing ACLs at retrieval is necessary but not
sufficient**: a filtered context looks to the model exactly like a complete one, and
an 8B model will not emit a refusal token while it holds anything topically related.
Two real fixes, neither of them more prompt tuning:

- a deterministic claim-support check — reject an answer asserting a term that
  appears in no cited card, which is the same trick as the extraction grounding gate
  and equally cheap;
- tell the model when facts were withheld, so "I cannot see everything" is in the
  context rather than left to inference.

Worth stating plainly because it is the failure the job description's access-control
requirement is really about, and it is invisible unless you ask the same question
twice as two different readers. `Q5` in the harness is now that regression test.

**Q4 is a graph fault, not a generation one.** Asked whether the newsletter is being
discontinued, the system declines — because the fact that answers it, "the newsletter
will be kept", is marked superseded. The Notion page that *reaffirms* the decision was
judged a revision of it, closing the only card that held the answer. Same shape as an
earlier bug where "the launch is done" closed "the launch is June 1".

**Supersession precision is the system's weakest link**, and it now has a measured
cost rather than an opinion attached to it. Four adjudicator prompt revisions moved
it very little: an 8B model asked whether a restatement revises a fact says yes far
too often, and even forcing it to name what changed did not help — it always names
something. The honest fixes are a stronger adjudicator model, or a mechanism that
does not rely on one, not more prompt tuning.

## What "200 concurrent users" actually costs

The job this was designed against asks for 200 concurrent users, so that claim got
measured rather than asserted. `scripts/loadtest/LoadTest.java` runs with plain
`java LoadTest.java` — no install, no build step — so the numbers below are
reproducible by anyone who clones the repo.

Two endpoints exist for exactly this reason: `/api/retrieve` stops after assembling
context, `/api/ask` also generates. Measured together, every result reads as "the
model is slow" and tells you nothing about your own system.

**Retrieval — the half that is ours to make fast.** Concurrency ramp, 15s per level:

| concurrent | req/s | p50 | p99 | errors |
|---|---|---|---|---|
| 1 | 191 | 4ms | 10ms | 0 |
| 10 | 1,500 | 6ms | 11ms | 0 |
| 50 | 2,304 | 20ms | 39ms | 0 |
| **200** | **2,314** | **83ms** | **147ms** | **0** |

Getting there took two fixes, and the second only became visible after the first:

| at 200 concurrent | req/s | p99 | errors |
|---|---|---|---|
| baseline | 245 | 879ms | 0 |
| + embedding cache | 1,912 | 134ms | **15,811** |
| + Neo4j pool raised to 300 | **2,314** | 147ms | 0 |

Throughput originally flattened at ~245 req/s from 10 concurrent upward — textbook
queueing (245 req/s × 813ms ≈ 200 in flight). The constraint was that **every
retrieval embeds its question before it can touch the index**, turning a graph query
into a network round trip. Caching those vectors gave 8×.

Which then exposed the next limit: the Neo4j driver defaults to a 100-connection
pool, so at 200 concurrent the pending-acquisition queue overflowed and requests
failed with `TransientException` instead of queueing. Not a bug — a default sized for
a different workload. Removing one bottleneck reveals the next, which is the whole
reason to ramp rather than measure one level.

**Generation — the half that is not.**

| concurrent | req/s | p50 | p99 |
|---|---|---|---|
| 1 | 1.0 | 908ms | 2.6s |
| 4 | 1.1 | 3.3s | 6.3s |
| 16 | **0.3** | 30.5s | 80.2s |

Throughput is flat at ~1 req/s no matter the concurrency, and *falls* at 16 — a
single local 8B model thrashing between requests rather than serializing them.

**So the honest answer to "does it handle 200 concurrent users" is: the application
does, by a factor of ~2,300; the model does not, by a factor of ~1.** Serving that
many people is a question of model capacity — replicas, a hosted provider with real
concurrency, or an answer cache — and almost nothing to do with the application
architecture. Worth knowing which of the two you are actually buying when you scale.

Two caveats, since a benchmark is only worth its method. The load test asks eight
fixed questions, so after warm-up the embedding cache hits ~100% — a best case that
real traffic with unique questions would erode back toward the 245 req/s baseline.
And 200 concurrent *users* is not 200 concurrent *requests*: people read between
questions, so this measures a far heavier load than 200 users would generate.

```bash
./gradlew :hive-app:bootRun &                                   # server on :8080
java scripts/loadtest/LoadTest.java --endpoint retrieve --ramp 1,10,50,200
java scripts/loadtest/LoadTest.java --endpoint ask --ramp 1,4,16 --seconds 25
```

## Handing it to an agent

```bash
./gradlew :hive-app:bootRun --args='mcp'                        # stdio
./gradlew :hive-app:bootRun --args='mcp --grants=slack:C_EXEC'  # …as a reader who can see more
./gradlew :hive-app:bootRun                                     # …or POST /mcp alongside the REST API
```

Four tools, over the graph that is already there:

| Tool | Answers |
|---|---|
| `search_knowledge` | What did we decide about X — as numbered cards, optionally as of a past date |
| `trace_decision` | How did this get to where it is — every version, oldest first, both directions from the seed |
| `find_owner` | Who is on the hook — from the resolved `OWNED_BY` edge, never from the wording |
| `path_between` | How are these two connected at all — shortest routes, with each edge's direction |

**The tools retrieve; they do not answer.** That is the whole shape of the thing.
`/api/ask` runs retrieval *and* generation because an HTTP caller wants a sentence.
An MCP caller **is** the model, so the half worth exposing is the one that stops at
the context pack — the same seam `/api/retrieve` was already split along for load
testing, which is a good sign it is a real seam and not a convenience.

**The caller does not get to name its own grants.** `/api/ask` takes them in the
request body and says in its own comments that this is a development affordance. That
affordance cannot come along here: a tool argument is written by a model, from text
that may have come out of the corpus, so a `grants` parameter is an instruction to
escalate and the model has no reason to decline it. The reader is fixed when the
server starts — `--grants=` on stdio, `hive.mcp.grants` over HTTP — and no argument
moves it. There is a test asserting no tool declares one, because this is the sort of
thing a helpful refactor adds back.

### The leak-by-inference fix, finally landed

The failure documented above — a reader without the exec grants gets the public
fragments of a restricted topic and reasons a confident answer out of them — gets
strictly worse over MCP, because the generating model is now outside this codebase
entirely. There is no prompt left to tighten. So the second of the two fixes named
above is the one that shipped: **tell the caller when facts were withheld.**

```
Q: Is there a hiring freeze?          (no special access)
   4 facts, read as a reader with no special access.
   At least 7 further facts match this question and are outside this reader's access.
   Answer only from the cards below, and say the record is incomplete rather than
   inferring what the withheld facts might say.

Q: Is there a hiring freeze?          (holding the exec grants)
   3 facts, read as a reader holding notion:P_BOARD_Q1, slack:C_EXEC, zoom:M_EXEC_OFFSITE.
   [3] DECISION — CURRENT (AGREED)  The hiring freeze is in place through the end of Q3.
```

Two things about that count. It is a **floor, not an estimate** — the keyword half of
the seed only, so no embedding round trip and the honest phrasing is "at least". And
it discloses a number and nothing else: no statement, no date, no topic, no source.
That is still a policy choice rather than a free win — it converts "invisible" into
"acknowledged redaction", and a reader now learns that restricted material on this
topic exists. That trade is worth making here and might not be everywhere, which is
why it is one query in one place rather than a change to retrieval.

### Filtering a path is harder than filtering a list

Dropping rows a reader may not have is easy. A **path** is not, because a path that
runs through a fact you cannot read is itself a disclosure — it asserts two things are
connected and the only reason to believe it is the hidden step in the middle. So every
traversal carries the predicate into the expansion rather than applying it to the
result: `ALL(n IN nodes(path) WHERE …)`, not a filter on the endpoints. A chain stops
at the first link the reader cannot see, and a path needing an unreadable node does not
exist as far as they are concerned.

The same reasoning removed two edge types from `path_between` entirely. `EVIDENCED_BY`
and `SPOKEN_BY` run through `Utterance`, which carries no `aclGrants` of its own — it
inherits its episode's — and the expansion cannot do that join while it walks. Rather
than return a path whose readability was never actually checked, those edges are out,
and people connect through the facts they own, are involved in, and the topics those
are about. Which is enough:

```
3 shortest paths between Person "Alex Chen" and Topic "nordwind", 4 hops.

  1. Person "Alex Chen"
       <-[OWNED_BY]-  Fact "The cost delta for migrating to Mux is roughly neutral."  (current)
       -[INVOLVES]->  Person "Priya Raghunathan"
       <-[INVOLVES]-  Fact "Nothing is committed regarding the Nordwind co-production."  (current)
       -[ABOUT]->     Topic "nordwind"
```

Asked for `Alex`, it returns both Alexes and asks which — the identity decoy answered
by refusing to guess. Asked for a person's email or a Slack handle, it resolves through
the same directory identity resolution used at load time.

### Two things it turned up

**`trace_decision` walks straight into the Q4 bug and shows it.** Asked about the
newsletter, it returns the fact that answers the question *and* the reason `ask`
declines to use it:

```
  2026-03-19  SUPERSEDED  The newsletter will be kept.
              true from 2026-03-19 until 2026-03-19
  2026-03-19  CURRENT     The decision to keep the newsletter is not up for review before 2027 planning.
```

A validity interval of zero width. The Notion page that *reaffirms* a decision was
judged a revision of it, so the fact was born and closed on the same day. This does
not fix the false supersession — supersession precision is still the weakest link —
but handing an agent the chain instead of a filtered current-state view means the
answer survives the bug, and a zero-length interval is a cheap thing to detect.

**stdout is a wire, not a console.** On stdio, one Spring startup line lands in the
middle of a JSON-RPC frame and the client drops the session with a parse error naming
nothing. `System.out` is pointed at stderr before Spring starts and the transport is
handed the file descriptor directly — more reliable than finding every writer, since
it also catches logging, libraries, and the `System.out.printf` the other CLI runners
are built on.

### Pointing a client at it

```json
{
  "mcpServers": {
    "hive-mind": {
      "command": "java",
      "args": ["-jar", "/path/to/hive-app-0.1.0-SNAPSHOT.jar", "mcp",
               "--grants=slack:C_EXEC"]
    }
  }
}
```

Use the built jar rather than `bootRun` — Gradle writes to stdout too, and stdout
belongs to the protocol.

The HTTP endpoint is single-principal for the same reason it has no auth: real
per-principal grants have to be materialised from the source systems, which is the
next milestone, and inventing a header to carry them meanwhile would look like
authentication without being any. Bind it to localhost or put it behind something
that knows who is calling.

## Browsing it

```bash
./gradlew :hive-app:bootRun --args='export'     # writes vault/
```

155 notes — one per fact, person, episode and topic — with typed edges as wikilinks,
so Obsidian's graph view shows the clustering the corpus actually has. Superseded
facts keep their notes and link forward to what replaced them, which makes a
decision's history walkable by clicking rather than only by querying.

Neo4j is the store and this is a view; neither replaces the other. Obsidian has one
untyped link and no query language, so it cannot answer "which decisions were reversed
after March and who owns the follow-up" — and a Cypher result set is a poor thing to
browse on a Sunday.

## Modules

| Module | Holds |
|---|---|
| `hive-core` | Domain and ontology. No Spring, no framework — the rules live somewhere a reader can find them. |
| `hive-ingest` | Connectors and episode assembly. Turns raw source content into bounded discourse. |
| `hive-extract` | Schema-constrained LLM extraction, the grounding gate, entity resolution. |
| `hive-graph` | Neo4j persistence, bi-temporal writes, supersession. |
| `hive-retrieval` | Hybrid seed → expand → rerank → context assembly. |
| `hive-eval` | Gold set, extraction precision/recall, answer faithfulness. |
| `hive-app` | Spring Boot: REST, MCP server, CLI, wiring. |

## Design decisions

**Neo4j for graph *and* vectors.** Native vector indexes since 5.13 mean semantic
seed and structural expansion happen in one Cypher round trip instead of an
application-side join between two stores. Postgres keeps the boring half —
principals, roles, ingest cursors, audit.

**Java 25 LTS on Spring Boot 4.1.** Every request here blocks on an LLM or on Neo4j;
virtual threads carry that concurrency without a reactive rewrite. Most Graph RAG
lives in Python, which makes a production-shaped JVM implementation worth having.

**Flat fact records, not a sealed hierarchy.** A polymorphic schema costs accuracy
exactly where it hurts: models pick variants badly and strict JSON Schema handles
`oneOf` unevenly across providers. One shape with a type discriminator plus
per-type required fields gets the same guarantees and extracts better.

**Ontology version travels with every fact.** Extraction quality is measured per
prompt and per schema. Without a version stamped on the fact there is no way to
tell an improvement from a regression, or to re-extract only what an old schema
produced.

## Roadmap

- [x] **M1.0** — Scaffold, Docker stack, `doctor`
- [x] **M1.1** — Domain model, ontology, ACL inheritance, grounding gate
- [x] **M1.2** — Meridian Media corpus: 26 hand-authored episodes with planted decision reversals, identity puzzles, and cross-source references. Runs the whole demo with zero credentials, and doubles as the eval gold set.
- [x] **M1.3** — Connectors and schema-constrained extraction
- [x] **M1.4** — Entity resolution and bi-temporal graph writes
- [x] **M1.5** — Hybrid retrieval and cited answers
- [x] **M1.6** — Obsidian vault export and the answer-level eval harness
**M2 — real connectors.** Not started. The readers already parse the live wire
formats, since the sample corpus is written in them, so what remains is auth,
pagination, incremental cursors, rate limits, and handling edits and deletes by
tombstoning a source and invalidating the facts derived from it. All of it needs
credentials to verify against the real APIs.

- [ ] Slack — socket mode for live events, `conversations.history` for backfill
- [ ] Notion — incremental sync on `last_edited_time`
- [ ] Zoom — recording webhooks, plus Whisper and diarisation for audio-only meetings

**M3 — production concerns.** Two of four done.

- [x] REST API (`/api/ask`, `/api/retrieve`) and load test at 200 concurrent
- [x] MCP server — `search_knowledge`, `trace_decision`, `find_owner`, `path_between` over stdio and streamable HTTP
- [ ] RBAC — materialise per-principal grants from the source systems and cache them in Redis, which is in the compose file and currently unused. The grant model and its enforcement in Cypher are built; what is missing is where real grants come from
- [ ] Slack app — `/hive` command and thread mentions

## License

TBD.
