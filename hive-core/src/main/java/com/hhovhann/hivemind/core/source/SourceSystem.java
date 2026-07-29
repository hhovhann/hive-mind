package com.hhovhann.hivemind.core.source;

/** Where a piece of raw content came from. */
public enum SourceSystem {
    SLACK,
    NOTION,
    ZOOM,
    /** Local files — how the bundled sample corpus and any drop-folder import arrive. */
    FILESYSTEM
}
