package io.github.bhushanchinmay.botdesk.project;

import io.github.bhushanchinmay.botdesk.json.Obj;

import java.util.Map;

/**
 * How one kind of work is done (feature, bugfix, bughunt, maintenance, prototype, investigate).
 * One JSON file in playbooks/. The rules that matter most are enforced in code:
 * requireTestChange (the change must touch a test), allowEmptyDiff (a report with no code change is fine)
 * readOnly (any code change is a failure, for support questions), and checksFirst (run the checks before
 * any model; if they all pass there is nothing to do, so no tokens are spent).
 */
public record Playbook(
        String kind,
        String description,
        String leadGuide,
        String engineerGuide,
        String reviewRubric,
        boolean requireTestChange,
        boolean allowEmptyDiff,
        boolean readOnly,
        boolean checksFirst) {

    public static Playbook fromMap(Map<String, Object> m) {
        return new Playbook(
                Obj.str(m, "kind", ""),
                Obj.str(m, "description", ""),
                Obj.str(m, "leadGuide", ""),
                Obj.str(m, "engineerGuide", ""),
                Obj.str(m, "reviewRubric", ""),
                Obj.bool(m, "requireTestChange", true),
                Obj.bool(m, "allowEmptyDiff", false),
                Obj.bool(m, "readOnly", false),
                Obj.bool(m, "checksFirst", false));
    }
}
