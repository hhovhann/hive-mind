package com.hhovhann.hivemind.core.episode;

/**
 * The shape of a bounded unit of discourse.
 *
 * <p>Kind drives extraction: a meeting transcript needs speaker attribution and
 * tolerates rambling, a Notion page is authored and edited, a Slack thread has a
 * root message that frames every reply.
 */
public enum EpisodeKind {
    SLACK_THREAD,
    SLACK_CHANNEL_WINDOW,
    MEETING_TRANSCRIPT,
    NOTION_PAGE,
    DOCUMENT
}
