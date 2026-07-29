"""Hand-authored sample corpus for a fictional media company, Meridian Media.

Every episode here is written by hand rather than generated, because this corpus is
also the eval gold set: each storyline plants a specific failure mode that naive
chunk-and-embed retrieval gets wrong, and a generated corpus cannot promise that.

Content lives in the sibling modules; this one holds the cast, the channels, and the
time helpers so identifiers and timestamps stay consistent across all three sources.
"""

from datetime import datetime, timedelta, timezone

# --------------------------------------------------------------------------------
# Cast
#
# Two people are called Alex. That is deliberate: Alexandra Petrova appears as
# "Alex P." in Slack, "Alexandra Petrova" in Notion, and an unnamed speaker label in
# Zoom, so resolving her across sources is the whole job — while Alex Chen sits next
# to her as a decoy that must NOT be merged into the same person.
# --------------------------------------------------------------------------------

PEOPLE = [
    {
        "slack_id": "U_APETROVA",
        "handle": "apetrova",
        "display_name": "Alex P.",
        "real_name": "Alexandra Petrova",
        "email": "alexandra.petrova@meridian.media",
        "title": "VP Content",
    },
    {
        "slack_id": "U_ACHEN",
        "handle": "achen",
        "display_name": "Alex Chen",
        "real_name": "Alex Chen",
        "email": "alex.chen@meridian.media",
        "title": "Staff Engineer",
    },
    {
        "slack_id": "U_PRIYA",
        "handle": "priya",
        "display_name": "Priya R.",
        "real_name": "Priya Raghunathan",
        "email": "priya.raghunathan@meridian.media",
        "title": "Head of Operations",
    },
    {
        "slack_id": "U_MARCUS",
        "handle": "marcus",
        "display_name": "Marcus Webb",
        "real_name": "Marcus Webb",
        "email": "marcus.webb@meridian.media",
        "title": "Chief Executive Officer",
    },
    {
        "slack_id": "U_DANA",
        "handle": "dana",
        "display_name": "Dana O.",
        "real_name": "Dana Okonkwo",
        "email": "dana.okonkwo@meridian.media",
        "title": "Head of Post-Production",
    },
    {
        "slack_id": "U_TOMB",
        "handle": "tomb",
        "display_name": "Tom B.",
        "real_name": "Tom Bergstrom",
        "email": "tom.bergstrom@meridian.media",
        "title": "Newsletter Editor",
    },
    {
        "slack_id": "U_SOFIA",
        "handle": "sofia.m",
        "display_name": "Sofia M.",
        "real_name": "Sofia Marques",
        "email": "sofia.marques@meridian.media",
        "title": "Content Intern",
    },
    {
        "slack_id": "U_JULES",
        "handle": "jules",
        "display_name": "Jules R.",
        "real_name": "Jules Rivera",
        "email": "jules.rivera@meridian.media",
        "title": "Head of Partnerships",
    },
]

BY_HANDLE = {person["handle"]: person for person in PEOPLE}

# --------------------------------------------------------------------------------
# Channels
#
# #exec is RESTRICTED. Facts derived partly from it must stay unreadable to anyone
# without that grant, even when the other half came from #general.
# --------------------------------------------------------------------------------

CHANNELS = [
    {"id": "C_GENERAL", "name": "general", "visibility": "PUBLIC"},
    {"id": "C_CONTENT", "name": "content-prod", "visibility": "PUBLIC"},
    {"id": "C_ENG", "name": "eng", "visibility": "PUBLIC"},
    {"id": "C_FRONTIER", "name": "frontier-launch", "visibility": "PUBLIC"},
    {"id": "C_EXEC", "name": "exec", "visibility": "RESTRICTED"},
]

BY_CHANNEL_ID = {channel["id"]: channel for channel in CHANNELS}

WORKSPACE = "meridianmedia"


def at(iso: str) -> datetime:
    """Parse an ISO-8601 UTC instant."""
    return datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(timezone.utc)


def slack_ts(moment: datetime, sequence: int = 0) -> str:
    """Slack message id: epoch seconds with a six-digit suffix."""
    return f"{int(moment.timestamp())}.{sequence:06d}"


def plus(moment: datetime, minutes: float) -> datetime:
    return moment + timedelta(minutes=minutes)


def permalink(channel_id: str, ts: str) -> str:
    return f"https://{WORKSPACE}.slack.com/archives/{channel_id}/p{ts.replace('.', '')}"
