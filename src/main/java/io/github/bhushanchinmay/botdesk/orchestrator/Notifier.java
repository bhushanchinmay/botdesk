package io.github.bhushanchinmay.botdesk.orchestrator;

/** Pushes "needs you" alerts somewhere you will see them (Slack, or nowhere). */
@FunctionalInterface
public interface Notifier {
    void notify(String text);

    Notifier NONE = text -> { };
}
