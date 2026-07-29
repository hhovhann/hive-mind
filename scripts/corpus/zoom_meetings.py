"""Zoom cloud-recording transcripts.

Turns are ``(speaker_label, start_seconds, duration_seconds, text)``.

The first meeting deliberately carries unresolved ``Speaker N`` labels, which is what
Zoom actually produces when it cannot match a voice to an account. Recovering who is
who from the participant list and what they say is the hardest identity join in the
corpus, and it is the one that unlocks the whole Frontier storyline — the original
May 4 decision was made in that room and only recapped in Slack afterwards.
"""

MEETINGS = [
    {
        "key": "content-sync-2026-02-11",
        "uuid": "mtg_8f2c41ab9e",
        "topic": "Weekly content sync",
        "started": "2026-02-11T09:00:00Z",
        "scope_id": "M_CONTENT_SYNC",
        "visibility": "PUBLIC",
        # Speaker 1 = Dana Okonkwo, Speaker 2 = Alexandra Petrova, Speaker 3 = Priya
        # Raghunathan. The mapping lives in ground_truth.py, not here.
        "participants": [
            {"name": "Alexandra Petrova", "email": "alexandra.petrova@meridian.media"},
            {"name": "Dana Okonkwo", "email": "dana.okonkwo@meridian.media"},
            {"name": "Priya Raghunathan", "email": "priya.raghunathan@meridian.media"},
        ],
        "turns": [
            ("Speaker 2", 4, 8, "Okay, Frontier. We need a premiere date today, I don't want to come back to this next week."),
            ("Speaker 1", 13, 7, "Post can hit early May if nothing else lands on us. I'd say the fourth."),
            ("Speaker 2", 26, 6, "May the fourth. Is that with or without a second cut round?"),
            ("Speaker 1", 35, 8, "With. One round on each of the six, plus a buffer week at the end."),
            ("Speaker 2", 48, 6, "Then let's call it. Frontier premieres May the fourth."),
            ("Speaker 3", 58, 7, "I'll build the promo calendar around that and send it round tomorrow."),
            ("Speaker 2", 67, 9, "Thanks. And can someone write it down this time, I don't want it living only in this call."),
        ],
    },
    {
        "key": "nordwind-call-2026-03-05",
        "uuid": "mtg_5b19d7c204",
        "topic": "Nordwind Studios — co-production discussion",
        "started": "2026-03-05T14:00:00Z",
        "scope_id": "M_NORDWIND",
        "visibility": "PUBLIC",
        "participants": [
            {"name": "Jules Rivera", "email": "jules.rivera@meridian.media"},
            {"name": "Marcus Webb", "email": "marcus.webb@meridian.media"},
            {"name": "Ingrid Halvorsen", "email": "i.halvorsen@nordwindstudios.no"},
        ],
        "turns": [
            ("Jules Rivera", 6, 9, "Thanks for making time. On our side we'd want to be clear what a co-production actually means before we talk numbers."),
            ("Ingrid Halvorsen", 17, 8, "Two series a year. We fund production, you handle distribution in your markets."),
            ("Marcus Webb", 28, 3, "And the split?"),
            ("Ingrid Halvorsen", 33, 7, "Sixty forty, our way. That reflects where the production risk sits."),
            ("Jules Rivera", 42, 8, "That is not a starting point I can work with, Ingrid. We are bringing the audience."),
            ("Ingrid Halvorsen", 53, 7, "Then let us leave the split for now and keep talking about the slate."),
            ("Marcus Webb", 63, 7, "We'll come back to you once we have modelled it on our side."),
        ],
    },
    {
        "key": "exec-offsite-2026-04-22",
        "uuid": "mtg_c740e28f1d",
        "topic": "Exec offsite — Q2 planning",
        "started": "2026-04-22T13:00:00Z",
        "scope_id": "M_EXEC_OFFSITE",
        "visibility": "RESTRICTED",
        "participants": [
            {"name": "Marcus Webb", "email": "marcus.webb@meridian.media"},
            {"name": "Alexandra Petrova", "email": "alexandra.petrova@meridian.media"},
            {"name": "Priya Raghunathan", "email": "priya.raghunathan@meridian.media"},
            {"name": "Jules Rivera", "email": "jules.rivera@meridian.media"},
        ],
        "turns": [
            ("Marcus Webb", 5, 9, "The Q1 numbers are eleven percent under plan and the ad market is not coming back this year."),
            ("Priya Raghunathan", 16, 3, "What are the levers?"),
            ("Marcus Webb", 21, 10, "Headcount is the only one that moves fast enough. I want a hiring freeze across all departments through the end of Q3."),
            ("Alexandra Petrova", 33, 6, "Including content? We are already short two producers."),
            ("Marcus Webb", 41, 8, "Including content. No new reqs and no backfills without my sign-off."),
            ("Jules Rivera", 51, 4, "Do we say anything publicly?"),
            ("Marcus Webb", 57, 11, "Not yet. I will tell the leads, and we say nothing wider until we know whether Q3 recovers."),
        ],
    },
    {
        "key": "ops-sync-2026-06-17",
        "uuid": "mtg_2ae6b9f3c8",
        "topic": "Ops sync",
        "started": "2026-06-17T10:00:00Z",
        "scope_id": "M_OPS_SYNC",
        "visibility": "PUBLIC",
        "participants": [
            {"name": "Priya Raghunathan", "email": "priya.raghunathan@meridian.media"},
            {"name": "Marcus Webb", "email": "marcus.webb@meridian.media"},
            {"name": "Dana Okonkwo", "email": "dana.okonkwo@meridian.media"},
        ],
        "turns": [
            ("Priya Raghunathan", 8, 9, "Last thing — the Dunmore Street studio. The landlord has given us until the end of September at the current rate."),
            ("Marcus Webb", 19, 3, "And after that?"),
            ("Priya Raghunathan", 24, 7, "Eighteen percent up. It is a five year lease, so that compounds badly."),
            ("Marcus Webb", 33, 6, "Then sign it before September. Can you own that?"),
            ("Priya Raghunathan", 41, 6, "Yes. I will have the lease signed before the end of Q3."),
            ("Dana Okonkwo", 49, 8, "Does that mean we are committing to in-house podcast production for five years?"),
            ("Marcus Webb", 59, 9, "It means we are committing to the room. What we put in it is a separate conversation."),
        ],
    },
    {
        "key": "frontier-retro-2026-06-24",
        "uuid": "mtg_9d3f07ba55",
        "topic": "Frontier launch retro",
        "started": "2026-06-24T15:00:00Z",
        "scope_id": "M_FRONTIER_RETRO",
        "visibility": "PUBLIC",
        "participants": [
            {"name": "Alexandra Petrova", "email": "alexandra.petrova@meridian.media"},
            {"name": "Dana Okonkwo", "email": "dana.okonkwo@meridian.media"},
            {"name": "Priya Raghunathan", "email": "priya.raghunathan@meridian.media"},
            {"name": "Jules Rivera", "email": "jules.rivera@meridian.media"},
        ],
        "turns": [
            ("Alexandra Petrova", 6, 8, "Frontier landed. Let's talk about what we would do differently, not about whether it worked."),
            ("Dana Okonkwo", 17, 10, "We moved the date twice. Both times for good reasons, and both times it cost Priya a week of calendar work."),
            ("Priya Raghunathan", 30, 7, "Three times, technically. May the fourth, June the fifteenth, June the first."),
            ("Alexandra Petrova", 40, 9, "That one is on me. I locked a date in February on eleven weeks of post with no slack in it."),
            ("Dana Okonkwo", 52, 10, "The fix is not to lock later. It is to publish a range until picture lock and only then commit to a day."),
            ("Alexandra Petrova", 65, 12, "Agreed. From now on we announce a launch window, and we do not commit to a specific date until picture lock on the final episode."),
        ],
    },
]
