package io.github.bhushanchinmay.botdesk.orchestrator.stage;

import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BACKLOG -> CONFIRMING. The lead restates the request, writes a plan and "done means",
 * and asks you to approve before any code is written. A wrong plan is cheap to fix; wrong code is not.
 */
public final class PlanStage implements Stage {

    private static final int MAX_FILE_LIST_CHARS = 6_000;
    private static final String SYSTEM = """
            You are the tech lead of a small software team. You never write code.
            Turn the request into a short plan the engineer can follow, and acceptance criteria
            that can be checked by running the project's checks or by reading the diff.
            Keep the change as small as possible. Ask questions only if something is genuinely unclear.
            Return only JSON: {"restatement": "...", "plan": ["step", "..."], "acceptance": ["...", "..."], "questions": ["..."]}
            """;

    private final StageSupport support;

    public PlanStage(StageSupport support) { this.support = support; }

    @Override
    public Set<TaskState> handles() { return Set.of(TaskState.BACKLOG); }

    @Override
    public void run(Task task, Project project, Playbook playbook) {
        String system = SYSTEM + "\nGuide for this kind of work (" + playbook.kind() + "):\n" + playbook.leadGuide();
        String user = "Project: " + project.name() + "\nChecks: " + project.checks().keySet()
                + "\n\nTask: " + task.title() + "\n" + task.goal()
                + "\n\nRepository files:\n" + StageSupport.clip(support.listRepo(project), MAX_FILE_LIST_CHARS)
                + "\n\nProject context:\n" + support.projectContext(project);
        Map<String, Object> json = support.askJson(task, "lead", system, user);

        String plan = StageSupport.numbered(Obj.strings(json, "plan"));
        String acceptance = StageSupport.bullets(Obj.strings(json, "acceptance"));
        task.setPlan(plan, acceptance);
        support.services().store().save(task);

        List<String> questions = Obj.strings(json, "questions");
        var tasks = support.services().tasks();
        tasks.move(task, TaskState.CONFIRMING, "lead", "planned");
        tasks.raise(task, RequestKind.CONFIRM_TASK, "Lead's understanding:\n" + Obj.str(json, "restatement", "")
                + "\n\nPlan:\n" + plan + "\n\nDone means:\n" + acceptance
                + (questions.isEmpty() ? "" : "\n\nQuestions:\n" + StageSupport.bullets(questions))
                + "\n\nApprove to start, reply to change the plan, or reject to cancel.");
    }
}
