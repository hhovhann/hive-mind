"""Notion pages.

Notion edits in place. That is the trap in the Frontier plan below: the page states
the current launch date and carries no trace of the two dates before it, so a system
that trusts documents over conversations can tell you *what* was decided but never
*when it changed* or *why*. The history exists only in Slack and in a recording.

The page also contradicts itself — a promo section left pointing at the original May
calendar — because real documents do, and a knowledge base that cannot survive a
stale paragraph is not much use.
"""

PAGES = [
    {
        "key": "team-directory",
        "id": "P_TEAM_DIR",
        "title": "Team Directory",
        "created_time": "2026-01-08T10:00:00Z",
        "last_edited_time": "2026-02-02T11:24:00Z",
        "created_by": "Priya Raghunathan",
        "last_edited_by": "Priya Raghunathan",
        "visibility": "PUBLIC",
        "body": """Who does what at Meridian Media. Keep this current — it is the page
people check before they ask.

| Name | Title | Email |
| --- | --- | --- |
| Marcus Webb | Chief Executive Officer | marcus.webb@meridian.media |
| Alexandra Petrova | VP Content | alexandra.petrova@meridian.media |
| Priya Raghunathan | Head of Operations | priya.raghunathan@meridian.media |
| Dana Okonkwo | Head of Post-Production | dana.okonkwo@meridian.media |
| Jules Rivera | Head of Partnerships | jules.rivera@meridian.media |
| Alex Chen | Staff Engineer | alex.chen@meridian.media |
| Tom Bergstrom | Newsletter Editor | tom.bergstrom@meridian.media |
| Sofia Marques | Content Intern | sofia.marques@meridian.media |

Note: we have two Alexes. Alexandra Petrova goes by Alex and signs off as Alex P.
in Slack. Alex Chen is on the engineering side. If a thread says "Alex" and you
cannot tell which, look at the channel.""",
    },
    {
        "key": "frontier-launch-plan",
        "id": "P_FRONTIER_PLAN",
        "title": "Frontier — Launch Plan",
        "created_time": "2026-02-12T09:14:00Z",
        "last_edited_time": "2026-05-06T16:30:00Z",
        "created_by": "Alexandra Petrova",
        "last_edited_by": "Alexandra Petrova",
        "visibility": "PUBLIC",
        "body": """Six-part documentary series. Weekly drop after the premiere.

## Premiere

**June 1, 2026.** All six episodes delivered to the platform by May 28.

## Editorial

- Six episodes, 42–48 minutes each
- One cut round per episode. Episodes 5 and 6 ship on a single round — this was
  the trade we made to hold the June date
- Legal review complete on all episodes as of May 19

## Promo

Promo calendar owned by Priya. The May calendar is the source of truth for
partner assets and social scheduling — anything built against an earlier version
should be discarded.

## Open items

- Platform art for episodes 4–6 still outstanding
- Press embargo lifts 48 hours before premiere""",
    },
    {
        "key": "newsletter-strategy",
        "id": "P_NEWSLETTER",
        "title": "Newsletter Strategy 2026",
        "created_time": "2026-03-19T10:45:00Z",
        "last_edited_time": "2026-03-19T10:45:00Z",
        "created_by": "Alexandra Petrova",
        "last_edited_by": "Alexandra Petrova",
        "visibility": "PUBLIC",
        "body": """Writing this down so the question stops being reopened every quarter.

## Decision

We are keeping the newsletter. This was settled at exec level in March 2026 and
is not up for review again before 2027 planning.

## Why

The newsletter is our highest-converting channel into paid subscriptions, by
roughly four times the next best. Open rate sits around 12%, which is above
average for a list of our size — the number that matters here is conversion, not
open rate, and that is where the newsletter earns its place.

## Ownership

Tom Bergstrom owns editorial and the send schedule. Weekly, Thursday mornings.

## What we are changing

- Moving the subscribe prompt above the fold on article pages
- Testing a second monthly send focused on the documentary slate""",
    },
    {
        "key": "cms-migration-plan",
        "id": "P_CMS_MIGRATION",
        "title": "Video CMS Migration Plan",
        "created_time": "2026-03-10T14:20:00Z",
        "last_edited_time": "2026-03-10T17:05:00Z",
        "created_by": "Alex Chen",
        "last_edited_by": "Alex Chen",
        "visibility": "PUBLIC",
        "body": """Moving video off the legacy CMS and onto Mux.

## Why now

The legacy CMS has been the top source of on-call pages for three consecutive
weeks. Every incident traces to the same transcode queue deadlock, and we have
patched around it twice without fixing it.

## Cost

Roughly neutral at current volume. Mux usage cost is offset by retiring the two
EC2 instances running our own transcode workers.

## Plan

1. Dual-write new uploads to both systems — 2 weeks
2. Backfill the existing library — 4 weeks, throttled
3. Cut reads over to Mux behind a flag
4. Delete the legacy transcode path

## Risks

- Backfill of the 2019–2021 library may hit files with no usable master
- Player embed URLs change; anything hardcoded in old articles needs a rewrite""",
    },
    {
        "key": "nordwind-partnership",
        "id": "P_NORDWIND",
        "title": "Nordwind Studios — Partnership",
        "created_time": "2026-03-06T11:00:00Z",
        "last_edited_time": "2026-05-21T09:12:00Z",
        "created_by": "Jules Rivera",
        "last_edited_by": "Jules Rivera",
        "visibility": "PUBLIC",
        "body": """Norwegian production house. Talks began February 2026.

## Shape of the deal

Two co-produced series a year. Nordwind funds production, Meridian handles
distribution in our markets.

## Status

**Not agreed.** The revenue split is unresolved and has been since the first call
on March 5. Nordwind opened at 60/40 in their favour. Our position is 50/50 as a
floor, on the basis that we bring the audience, but that position has not been
formally mandated.

## Blocked on

- Financial model comparing 50/50, 55/45 and 60/40 across three volume scenarios.
  Requested by Marcus in March, not yet delivered.

## Do not

Commit to any split in writing until the model exists. Nordwind have asked twice.""",
    },
    {
        "key": "q2-roadmap",
        "id": "P_Q2_ROADMAP",
        "title": "Q2 2026 Roadmap",
        "created_time": "2026-04-01T08:30:00Z",
        "last_edited_time": "2026-06-02T14:40:00Z",
        "created_by": "Priya Raghunathan",
        "last_edited_by": "Priya Raghunathan",
        "visibility": "PUBLIC",
        "body": """## Content

- **Frontier** — six-part documentary series. Shipped June 1.
- Podcast slate — three new shows, dependent on studio capacity
- Newsletter — continuing weekly, second monthly send in test

## Engineering

- Video CMS migration to Mux, owned by Alex Chen, target end of Q2
- Player performance work, deferred to Q3

## Partnerships

- Nordwind co-production — in discussion, nothing committed

## Operations

- Dunmore Street studio lease — decision needed before the September rate change""",
    },
    {
        "key": "board-update-q1",
        "id": "P_BOARD_Q1",
        "title": "Board Update — Q1 2026",
        "created_time": "2026-04-27T18:00:00Z",
        "last_edited_time": "2026-04-28T09:15:00Z",
        "created_by": "Marcus Webb",
        "last_edited_by": "Marcus Webb",
        "visibility": "RESTRICTED",
        "body": """## Financial position

Q1 revenue came in 11% under plan. The shortfall is almost entirely display
advertising; subscriptions were marginally ahead.

## Actions taken

A hiring freeze is in effect across all departments through the end of Q3. No new
requisitions and no backfills without CEO sign-off. The posted video producer role
has been withdrawn.

We have not communicated this beyond the leadership team. The intention is to
avoid a retention problem while we still expect Q3 to recover.

## Outlook

If Q3 tracks to plan we lift the freeze in October. If it does not, we will need a
harder conversation about the size of the post-production team.""",
    },
]
