package io.github.bhushanchinmay.botdesk.quality;

import java.util.List;

/**
 * One quality rule, enforced in plain code before the reviewer model sees a change.
 *
 * <p>A gate returns the problems it found, worded so the engineer model can fix them: the list is
 * sent back as feedback. Return an empty list when the change passes. Gates must be pure: no I/O,
 * no model calls, so the same input always gives the same verdict.
 *
 * <p>To add a rule, implement this interface and pass it to {@link QualityGates#with}, or add it
 * to {@link QualityGates#defaults()} if every project should get it.
 */
public interface Gate {

    /** Short name for logs and the task timeline, for example "secret-scan". */
    String name();

    List<String> check(GateInput input);
}
