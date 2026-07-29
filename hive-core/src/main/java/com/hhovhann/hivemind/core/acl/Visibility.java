package com.hhovhann.hivemind.core.acl;

/**
 * How widely a scope is readable. Ordered least to most restrictive, so
 * {@link #compareTo} answers "which of these two is tighter".
 */
public enum Visibility {

    /** Anyone in the workspace can read it — a public Slack channel, a shared Notion page. */
    PUBLIC,

    /** Readable by a named group: a private channel, a team space. Needs an explicit grant. */
    RESTRICTED,

    /** Readable by named individuals only — a DM, a personal page. Needs an explicit grant. */
    PRIVATE;

    public boolean requiresGrant() {
        return this != PUBLIC;
    }

    public static Visibility mostRestrictive(Visibility left, Visibility right) {
        return left.compareTo(right) >= 0 ? left : right;
    }
}
