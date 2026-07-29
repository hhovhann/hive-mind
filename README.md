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

# 8. Run it as a server
./gradlew :hive-app:bootRun
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
| `hive-app` | Spring Boot: REST, CLI, wiring. |

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
- [ ] **M2** — Real connectors: Slack (socket mode + backfill), Notion, Zoom
- [ ] **M3** — RBAC, MCP server, Slack app, load test at 200 concurrent

## License

TBD.
