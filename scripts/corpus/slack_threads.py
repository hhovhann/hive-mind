"""Slack threads.

Each entry becomes one episode. Messages are ``(handle, minutes_after_start, text)``
— real Slack replies arrive minutes or hours apart, and those gaps matter: a reply
40 minutes later reads differently from an immediate one, and a system that
flattens a thread into a paragraph loses that.
"""

THREADS = [
    # ---------------------------------------------------------------- storyline 1
    # The Frontier launch date, decided three times. May 4 -> June 15 -> June 1.
    # This is the corpus's headline trap: flat retrieval returns all three dates
    # ranked by similarity and lets the model pick.
    {
        "key": "frontier-date-locked",
        "channel": "C_FRONTIER",
        "started": "2026-02-11T15:20:00Z",
        "messages": [
            (
                "apetrova",
                0,
                "Recap from this morning's sync so it's written down somewhere: "
                "Frontier premieres May 4. Six episodes, weekly drop after that.",
            ),
            (
                "dana",
                4,
                "May 4 works for post. That's eleven weeks from picture lock on ep 1, "
                "which is tight but doable as long as we don't add another cut round.",
            ),
            ("priya", 9, "Noted. I'll build the promo calendar around May 4."),
            ("apetrova", 12, "Thanks. Treat May 4 as locked unless post tells us otherwise."),
        ],
    },
    {
        "key": "frontier-date-slips",
        "channel": "C_CONTENT",
        "started": "2026-04-08T10:05:00Z",
        "messages": [
            (
                "dana",
                0,
                "We have a problem with Frontier. Eps 3 and 4 both need a full recut after "
                "the legal review, and I've lost two editors to the Halcyon freelance pool.",
            ),
            ("dana", 2, "Realistically I need six more weeks. May 4 is not happening."),
            ("apetrova", 15, "How firm is six weeks?"),
            (
                "dana",
                18,
                "Firm. I'd rather tell you six now than tell you four and come back asking again.",
            ),
            (
                "apetrova",
                24,
                "OK. We move Frontier to June 15. I'll tell Marcus. "
                "Priya, can you redo the promo calendar?",
            ),
            ("priya", 31, "Yes. Rebuilding around June 15."),
        ],
    },
    {
        "key": "frontier-date-pulled-forward",
        "channel": "C_FRONTIER",
        "started": "2026-05-06T09:40:00Z",
        "messages": [
            (
                "jules",
                0,
                "Heads up — Halcyon just announced their doc series for July 9. "
                "Same subject area, bigger name attached.",
            ),
            (
                "apetrova",
                6,
                "That changes things. If we're three weeks behind them we lose the news cycle entirely.",
            ),
            (
                "dana",
                14,
                "Eps 5 and 6 are the only ones not finished. I could get us to June 1 "
                "if we drop the second cut round on those two.",
            ),
            (
                "apetrova",
                19,
                "Do it. Frontier premieres June 1. That's final, we are not moving it again.",
            ),
            ("priya", 22, "June 1 it is. Third calendar. Living the dream."),
        ],
    },
    {
        "key": "frontier-live",
        "channel": "C_FRONTIER",
        "started": "2026-06-01T13:00:00Z",
        "messages": [
            ("priya", 0, "Frontier is live. All six episodes scheduled, ep 1 is out now."),
            ("dana", 8, "Congratulations everyone. That was a genuinely horrible three weeks."),
        ],
    },
    {
        "key": "frontier-week-one",
        "channel": "C_CONTENT",
        "started": "2026-06-10T11:15:00Z",
        "messages": [
            (
                "apetrova",
                0,
                "Frontier numbers after week one: 340k completed views on ep 1, "
                "well ahead of the 200k we modelled.",
            ),
            (
                "dana",
                6,
                "And we did it with one cut round on eps 5 and 6. Worth remembering "
                "next time someone tells us two rounds is non-negotiable.",
            ),
            ("apetrova", 11, "Noted, but let's not turn one data point into a policy."),
        ],
    },
    # ---------------------------------------------------------------- storyline 2
    # An intern floats killing the newsletter in public; the CEO settles it in a
    # private channel. Retrieval that ignores who is speaking reports the proposal
    # as the decision.
    {
        "key": "newsletter-proposal",
        "channel": "C_GENERAL",
        "started": "2026-03-17T14:02:00Z",
        "messages": [
            (
                "sofia.m",
                0,
                "Question from the intern corner: has anyone checked whether the newsletter "
                "is still worth keeping? Open rate is 12% and it eats most of Tom's week.",
            ),
            (
                "sofia.m",
                2,
                "Feels like we should just discontinue it and put that time into YouTube shorts instead.",
            ),
            ("tomb", 25, "12% is above industry average for our list size, for what it's worth"),
            ("achen", 40, "+1 to at least looking at the numbers properly before anyone decides"),
        ],
    },
    {
        "key": "newsletter-settled",
        "channel": "C_EXEC",
        "started": "2026-03-18T08:30:00Z",
        "messages": [
            (
                "marcus",
                0,
                "Saw the newsletter thread in general. To be clear: the newsletter is not going "
                "anywhere. It is the highest-converting channel we have into paid, by a factor of four.",
            ),
            (
                "marcus",
                3,
                "We are keeping the newsletter. Consider that settled — I don't want it "
                "relitigated every quarter.",
            ),
            (
                "apetrova",
                20,
                "Understood. I'll write it into the strategy doc so it stops coming up.",
            ),
        ],
    },
    # ---------------------------------------------------------------- storyline 3
    # A restricted decision with a deliberately vague public trace. Anyone without
    # the #exec grant must not learn the freeze from the public half.
    {
        "key": "hiring-freeze-decision",
        "channel": "C_EXEC",
        "started": "2026-04-22T16:45:00Z",
        "messages": [
            (
                "marcus",
                0,
                "Confirming what we landed on at the offsite: hiring freeze across all "
                "departments through the end of Q3. No new reqs and no backfills without my sign-off.",
            ),
            ("priya", 5, "Including the video producer req that's already posted?"),
            ("marcus", 8, "Pull it down."),
        ],
    },
    {
        "key": "video-role-question",
        "channel": "C_GENERAL",
        "started": "2026-04-24T11:20:00Z",
        "messages": [
            ("sofia.m", 0, "Is the video producer role still open? A friend of mine was going to apply."),
            (
                "priya",
                47,
                "Tell them to hold off for now — that one's on pause. "
                "I'd rather not say more than that here.",
            ),
        ],
    },
    # ---------------------------------------------------------------- storyline 4
    # Something explicitly never resolved. Asked months later it is still open, and
    # the failure mode is a system that invents a tidy resolution.
    {
        "key": "nordwind-debrief",
        "channel": "C_GENERAL",
        "started": "2026-03-06T09:15:00Z",
        "messages": [
            (
                "jules",
                0,
                "Nordwind call yesterday went well overall. They want to co-produce two series "
                "a year and they're willing to put real money behind it.",
            ),
            (
                "jules",
                3,
                "We did not get anywhere on the revenue split though. They opened at 60/40 "
                "their way, I said that wasn't a starting point I could work with, and we left it there.",
            ),
            ("marcus", 30, "What's our floor?"),
            (
                "jules",
                35,
                "I'd want 50/50 minimum given we bring the distribution. But I don't have a mandate yet.",
            ),
            ("marcus", 41, "Let's not agree to anything until we've modelled it properly."),
        ],
    },
    {
        "key": "nordwind-still-open",
        "channel": "C_GENERAL",
        "started": "2026-05-20T15:30:00Z",
        "messages": [
            (
                "apetrova",
                0,
                "Where did we land with Nordwind in the end? I'm scoping Q3 and I don't know "
                "whether to plan for a co-production or not.",
            ),
            (
                "jules",
                12,
                "Still open. We never settled the revenue split — it's been sitting behind "
                "the model Marcus asked for back in March.",
            ),
            ("jules", 14, "I'd plan Q3 without it and treat it as upside if it lands."),
        ],
    },
    # ---------------------------------------------------------------- storyline 5
    # "Alex owns the CMS migration" — in #eng, right after Alex Chen volunteers.
    # Merging the two Alexes into one person is the mistake to catch.
    {
        "key": "cms-migration-proposal",
        "channel": "C_ENG",
        "started": "2026-02-25T10:00:00Z",
        "messages": [
            (
                "achen",
                0,
                "The legacy video CMS is now the top source of on-call pages, three weeks "
                "running. Every single one is the same transcode queue deadlock.",
            ),
            (
                "achen",
                4,
                "I want to propose we migrate to Mux and delete the thing rather than keep patching it.",
            ),
            ("priya", 60, "What's the cost delta?"),
            (
                "achen",
                68,
                "Roughly neutral at our volume. We stop paying for the two EC2 boxes "
                "that run the transcode workers.",
            ),
        ],
    },
    {
        "key": "cms-migration-owner",
        "channel": "C_ENG",
        "started": "2026-03-11T09:05:00Z",
        "messages": [
            ("priya", 0, "Migration plan looks good to me. Who's owning this end to end?"),
            ("achen", 8, "I'll own it."),
            ("priya", 11, "Great — Alex owns the CMS migration, target end of Q2."),
        ],
    },
    # ---------------------------------------------------------------- storyline 6
    # "what we agreed in yesterday's ops sync" — the referent exists only in a Zoom
    # transcript. No amount of Slack-only retrieval can resolve it.
    {
        "key": "studio-lease-commitment",
        "channel": "C_GENERAL",
        "started": "2026-06-18T09:30:00Z",
        "messages": [
            (
                "priya",
                0,
                "Following up on what we agreed in yesterday's ops sync — I'll have the "
                "Dunmore Street studio lease signed before the end of Q3.",
            ),
            (
                "priya",
                2,
                "Flagging it early because if it slips past September the rate goes up 18%.",
            ),
            ("marcus", 40, "Good. Don't let it slip."),
        ],
    },
]
