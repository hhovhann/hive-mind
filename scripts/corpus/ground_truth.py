"""The answer key.

Kept strictly separate from the corpus itself — the pipeline never reads this — so
extraction and retrieval can be scored without being able to cheat. This is what
turns "the demo looked good" into a number that can be compared across prompt
versions.
"""

# --------------------------------------------------------------------------------
# Identity
#
# NON_MATCHES matters as much as ENTITIES: a resolver that merges everything scores
# perfectly on recall and destroys the graph. The two Alexes are the test.
# --------------------------------------------------------------------------------

ENTITIES = [
    {
        "canonical": "Alexandra Petrova",
        "email": "alexandra.petrova@meridian.media",
        "aliases": [
            {"system": "slack", "value": "U_APETROVA"},
            {"system": "slack", "value": "apetrova"},
            {"system": "slack", "value": "Alex P."},
            {"system": "notion", "value": "Alexandra Petrova"},
            {"system": "zoom", "value": "Alexandra Petrova"},
            # The hard one: an unresolved Zoom label, recoverable only from the
            # participant list plus what this speaker says.
            {"system": "zoom", "value": "content-sync-2026-02-11:Speaker 2"},
            {"system": "mention", "value": "Alex"},
        ],
    },
    {
        "canonical": "Alex Chen",
        "email": "alex.chen@meridian.media",
        "aliases": [
            {"system": "slack", "value": "U_ACHEN"},
            {"system": "slack", "value": "achen"},
            {"system": "slack", "value": "Alex Chen"},
            {"system": "notion", "value": "Alex Chen"},
            {"system": "mention", "value": "Alex"},
        ],
    },
    {
        "canonical": "Priya Raghunathan",
        "email": "priya.raghunathan@meridian.media",
        "aliases": [
            {"system": "slack", "value": "U_PRIYA"},
            {"system": "slack", "value": "priya"},
            {"system": "slack", "value": "Priya R."},
            {"system": "notion", "value": "Priya Raghunathan"},
            {"system": "zoom", "value": "Priya Raghunathan"},
            {"system": "zoom", "value": "content-sync-2026-02-11:Speaker 3"},
        ],
    },
    {
        "canonical": "Dana Okonkwo",
        "email": "dana.okonkwo@meridian.media",
        "aliases": [
            {"system": "slack", "value": "U_DANA"},
            {"system": "slack", "value": "dana"},
            {"system": "notion", "value": "Dana Okonkwo"},
            {"system": "zoom", "value": "Dana Okonkwo"},
            {"system": "zoom", "value": "content-sync-2026-02-11:Speaker 1"},
        ],
    },
    {
        "canonical": "Marcus Webb",
        "email": "marcus.webb@meridian.media",
        "aliases": [
            {"system": "slack", "value": "U_MARCUS"},
            {"system": "slack", "value": "marcus"},
            {"system": "notion", "value": "Marcus Webb"},
            {"system": "zoom", "value": "Marcus Webb"},
        ],
    },
    {
        "canonical": "Jules Rivera",
        "email": "jules.rivera@meridian.media",
        "aliases": [
            {"system": "slack", "value": "U_JULES"},
            {"system": "slack", "value": "jules"},
            {"system": "notion", "value": "Jules Rivera"},
            {"system": "zoom", "value": "Jules Rivera"},
        ],
    },
    {
        "canonical": "Ingrid Halvorsen",
        "email": "i.halvorsen@nordwindstudios.no",
        "external": True,
        "organisation": "Nordwind Studios",
        "aliases": [{"system": "zoom", "value": "Ingrid Halvorsen"}],
    },
]

NON_MATCHES = [
    {
        "pair": ["Alexandra Petrova", "Alex Chen"],
        "why": "Both are called Alex and both appear as owners of things. Merging them "
        "makes the VP of Content own a CMS migration and an engineer own the launch slate.",
    },
]

# --------------------------------------------------------------------------------
# Facts that should be extracted
# --------------------------------------------------------------------------------

FACTS = [
    {
        "id": "F1",
        "type": "DECISION",
        "statement": "Frontier premieres on May 4, 2026.",
        "owner": "Alexandra Petrova",
        "occurred_at": "2026-02-11T09:00:48Z",
        "status": "SUPERSEDED",
        "superseded_by": "F2",
        "sources": ["zoom:content-sync-2026-02-11", "slack:frontier-date-locked"],
        "acl": "public",
    },
    {
        "id": "F2",
        "type": "DECISION",
        "statement": "Frontier moves to June 15, 2026, because post-production needs six more weeks.",
        "owner": "Alexandra Petrova",
        "occurred_at": "2026-04-08T10:29:00Z",
        "status": "SUPERSEDED",
        "supersedes": "F1",
        "superseded_by": "F3",
        "sources": ["slack:frontier-date-slips"],
        "acl": "public",
    },
    {
        "id": "F3",
        "type": "DECISION",
        "statement": "Frontier premieres on June 1, 2026, pulled forward ahead of Halcyon's July 9 series.",
        "owner": "Alexandra Petrova",
        "occurred_at": "2026-05-06T09:59:00Z",
        "status": "AGREED",
        "supersedes": "F2",
        "sources": ["slack:frontier-date-pulled-forward", "notion:P_FRONTIER_PLAN"],
        "acl": "public",
        "note": "The current answer. Notion states this date but carries no trace of F1 or F2.",
    },
    {
        "id": "F4a",
        "type": "DECISION",
        "statement": "The newsletter is retained; the question is closed until 2027 planning.",
        "owner": "Marcus Webb",
        "occurred_at": "2026-03-18T08:33:00Z",
        "status": "AGREED",
        "sources": ["slack:newsletter-settled"],
        "acl": "requires slack:C_EXEC",
        "note": "Extracted from the private channel, so this instance is restricted.",
    },
    {
        "id": "F4b",
        "type": "DECISION",
        "statement": "The newsletter is retained.",
        "owner": "Alexandra Petrova",
        "occurred_at": "2026-03-19T10:45:00Z",
        "status": "AGREED",
        "sources": ["notion:P_NEWSLETTER"],
        "acl": "public",
        "note": "The same outcome, independently stated in a public document. A reader "
        "without the exec grant should reach this one and not F4a — the conclusion is "
        "public even though the deliberation is not.",
    },
    {
        "id": "F5",
        "type": "DECISION",
        "statement": "Hiring freeze across all departments through the end of Q3 2026; "
        "no new requisitions or backfills without CEO sign-off.",
        "owner": "Marcus Webb",
        "occurred_at": "2026-04-22T13:00:21Z",
        "status": "AGREED",
        "sources": ["zoom:exec-offsite-2026-04-22", "slack:hiring-freeze-decision", "notion:P_BOARD_Q1"],
        "acl": "requires zoom:M_EXEC_OFFSITE and slack:C_EXEC and notion:P_BOARD_Q1",
        "note": "Every source is restricted. The only public trace is Priya declining to "
        "explain why a role is paused, which must not be enough to reconstruct this.",
    },
    {
        "id": "F6",
        "type": "OPEN_QUESTION",
        "statement": "The revenue split with Nordwind Studios is unresolved.",
        "owner": "Jules Rivera",
        "occurred_at": "2026-03-05T14:00:42Z",
        "status": "PROPOSED",
        "sources": ["zoom:nordwind-call-2026-03-05", "slack:nordwind-debrief", "slack:nordwind-still-open", "notion:P_NORDWIND"],
        "acl": "public",
        "note": "Still open at the end of the corpus. Answering that a split was agreed is a failure.",
    },
    {
        "id": "F7",
        "type": "ACTION_ITEM",
        "statement": "Alex Chen owns the video CMS migration to Mux, targeting the end of Q2 2026.",
        "owner": "Alex Chen",
        "due_date": "2026-06-30",
        "occurred_at": "2026-03-11T09:16:00Z",
        "status": "IN_PROGRESS",
        "sources": ["slack:cms-migration-owner", "notion:P_CMS_MIGRATION", "notion:P_Q2_ROADMAP"],
        "acl": "public",
        "note": "Slack says only 'Alex owns the CMS migration'. Resolving that to Alexandra "
        "Petrova is the identity failure this fact exists to catch.",
    },
    {
        "id": "F8",
        "type": "COMMITMENT",
        "statement": "Priya Raghunathan will have the Dunmore Street studio lease signed "
        "before the end of Q3 2026.",
        "owner": "Priya Raghunathan",
        "due_date": "2026-09-30",
        "occurred_at": "2026-06-17T10:00:41Z",
        "status": "IN_PROGRESS",
        "sources": ["zoom:ops-sync-2026-06-17", "slack:studio-lease-commitment"],
        "acl": "public",
        "note": "The Slack message says only 'what we agreed in yesterday's ops sync'. "
        "Without the recording there is no agreement to point at.",
    },
    {
        "id": "F9",
        "type": "DECISION",
        "statement": "Launch dates are announced as a window; a specific date is committed "
        "only at picture lock on the final episode.",
        "owner": "Alexandra Petrova",
        "occurred_at": "2026-06-24T15:01:05Z",
        "status": "AGREED",
        "sources": ["zoom:frontier-retro-2026-06-24"],
        "acl": "public",
        "note": "A process decision that exists only in a recording, made in response to F1-F3.",
    },
]

# --------------------------------------------------------------------------------
# Eval questions
#
# `principal` names the grants the asker holds, so the ACL cases can be scored both
# ways: the same question must answer differently for different readers.
#
# Two kinds of prohibition, and the difference matters:
#   must_not_lead_with  the phrase may appear, but not as the answer. A superseded
#                       date is legitimate history and a misleading opening line.
#   must_not_say        the phrase may not appear at all. Reserved for leaks and for
#                       claims nobody made — there is no safe place to put those.
# --------------------------------------------------------------------------------

QUESTIONS = [
    {
        "id": "Q1",
        "question": "When does Frontier premiere?",
        "principal": [],
        "expected": "June 1, 2026.",
        "expected_facts": ["F3"],
        # Mentioning the earlier dates afterwards is useful; opening with one is the failure.
        "must_not_lead_with": ["May 4", "June 15"],
        "trap": "temporal — three decided dates exist and all three embed similarly",
    },
    {
        "id": "Q2",
        "question": "What was the Frontier launch date as of mid-April 2026?",
        "principal": [],
        "expected": "June 15, 2026 — moved from May 4 on April 8, and not yet pulled forward to June 1.",
        "expected_facts": ["F2"],
        "trap": "as-of query — needs validFrom/validTo, not just the current node",
    },
    {
        "id": "Q3",
        "question": "How many times did the Frontier launch date change, and why?",
        "principal": [],
        "expected": "Three dates. May 4 set on February 11; moved to June 15 on April 8 because "
        "episodes 3 and 4 needed recuts after legal review and two editors were lost; pulled "
        "forward to June 1 on May 6 after Halcyon announced a competing series for July 9.",
        "expected_facts": ["F1", "F2", "F3"],
        "trap": "supersession chain — requires walking SUPERSEDES, not ranking by similarity",
    },
    {
        "id": "Q4",
        "question": "Are we discontinuing the newsletter?",
        "principal": [],
        "expected": "No. It is retained — it is the highest-converting channel into paid subscriptions.",
        "expected_facts": ["F4b"],
        # Affirmative forms only. "We are not discontinuing the newsletter" is the
        # correct answer and contains the word; the failure is endorsing the proposal.
        "must_not_say": ["we are discontinuing", "is being discontinued", "will be discontinued"],
        "trap": "authority — an intern's proposal to kill it is the most on-topic text in the corpus",
    },
    {
        "id": "Q5",
        "question": "Is there a hiring freeze?",
        "principal": [],
        "expected": "Nothing retrievable confirms one. The video producer role is on pause, with no "
        "public reason given.",
        "expected_facts": [],
        "must_not_say": ["hiring freeze", "through the end of Q3", "11% under plan"],
        "trap": "ACL — every source for the freeze is restricted; the answer must not leak it",
    },
    {
        "id": "Q6",
        "question": "Is there a hiring freeze?",
        "principal": ["slack:C_EXEC", "zoom:M_EXEC_OFFSITE", "notion:P_BOARD_Q1"],
        "expected": "Yes — across all departments through the end of Q3 2026, decided at the April 22 "
        "exec offsite. No new requisitions or backfills without CEO sign-off.",
        "expected_facts": ["F5"],
        "trap": "the same question as Q5 must answer differently for a reader who holds the grants",
    },
    {
        "id": "Q7",
        "question": "Did we agree a revenue split with Nordwind?",
        "principal": [],
        "expected": "No. It is still open — Nordwind opened at 60/40 their way, our floor is 50/50, "
        "and it is blocked on a financial model Marcus requested in March.",
        "expected_facts": ["F6"],
        "must_not_say": ["we agreed", "50/50 was agreed", "settled at"],
        "trap": "invented resolution — the corpus contains numbers but no agreement",
    },
    {
        "id": "Q8",
        "question": "Who owns the video CMS migration?",
        "principal": [],
        "expected": "Alex Chen, Staff Engineer. Target end of Q2 2026.",
        "expected_facts": ["F7"],
        "must_not_say": ["Alexandra Petrova", "Alex P."],
        "trap": "identity decoy — two Alexes, and Slack says only 'Alex owns the CMS migration'",
    },
    {
        "id": "Q9",
        "question": "What did Priya commit to for the studio?",
        "principal": [],
        "expected": "Signing the Dunmore Street studio lease before the end of Q3 2026, to beat an "
        "18% rate increase after September.",
        "expected_facts": ["F8"],
        "trap": "cross-source anaphora — the Slack message points at an agreement made in a recording",
    },
    {
        "id": "Q10",
        "question": "Who set the original Frontier premiere date?",
        "principal": [],
        "expected": "Alexandra Petrova, in the content sync on February 11, 2026.",
        "expected_facts": ["F1"],
        "trap": "speaker resolution — she appears in that transcript only as 'Speaker 2'",
    },
]
