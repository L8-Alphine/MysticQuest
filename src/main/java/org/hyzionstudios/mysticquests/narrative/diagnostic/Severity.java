package org.hyzionstudios.mysticquests.narrative.diagnostic;

/** How serious a {@link Diagnostic} is. Any {@link #ERROR} fails a content reload. */
public enum Severity {
    ERROR,
    WARNING,
    INFO
}
