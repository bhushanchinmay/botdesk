package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.chat.ChatService;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.project.GitHub;

import java.net.http.HttpClient;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;

public final class ChatServiceTest {

    public static void testLeadCreatesTaskThenCommandsApproveIt() {
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead",
                "{\"reply\":\"On it.\",\"createTask\":{\"project\":\"calc\",\"kind\":\"bugfix\",\"title\":\"add() subtracts\",\"goal\":\"add(2,3) is -1\"}}",
                "{\"restatement\":\"r\",\"plan\":[\"p\"],\"acceptance\":[\"a\"]}");
        PipelineTest.Rig r = PipelineTest.rig(llm, "", new GitHub(null, HttpClient.newHttpClient()));
        var registry = Fixtures.registry(r.project());
        ChatService chat = new ChatService(r.store(), registry, r.tasks(), llm);

        String reply = chat.handle("add is broken in calc, please fix", "dashboard");
        check(reply.contains("Created task #1 (bugfix on calc)"), "task created from chat: " + reply);

        r.orch().tick(5);
        long requestId = r.store().openRequests().get(0).id();
        check(chat.handle("status", "slack").contains("1 request(s) waiting"), "status is plain code");
        check(chat.handle("approve " + requestId, "slack").startsWith("Done"), "approve command works");
        eq(TaskState.QUEUED, r.store().task(1).orElseThrow().state(), "queued after approval");
        eq(2, llm.calls("lead"), "commands never call the model");
    }

    public static void testUnknownProjectIsRejected() {
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead",
                "{\"reply\":\"ok\",\"createTask\":{\"project\":\"nope\",\"kind\":\"bugfix\",\"title\":\"x\",\"goal\":\"y\"}}");
        PipelineTest.Rig r = PipelineTest.rig(llm, "", new GitHub(null, HttpClient.newHttpClient()));
        ChatService chat = new ChatService(r.store(), Fixtures.registry(r.project()), r.tasks(), llm);
        check(chat.handle("do something", "dashboard").contains("Unknown project"), "clear error, no task");
        eq(0, r.store().tasks().size(), "nothing created");
    }

    // DNS rebinding: a hostile domain pointed at 127.0.0.1 still sends its own name as Host.
    public static void testDashboardRejectsUnknownHost() {
        java.util.Set<String> ok = java.util.Set.of("localhost", "127.0.0.1", "[::1]");
        check(io.github.bhushanchinmay.botdesk.web.DashboardServer.hostAllowed("localhost:8080", ok), "localhost");
        check(io.github.bhushanchinmay.botdesk.web.DashboardServer.hostAllowed("[::1]:8080", ok), "ipv6");
        check(!io.github.bhushanchinmay.botdesk.web.DashboardServer.hostAllowed("evil.example:8080", ok), "rebinding");
        check(!io.github.bhushanchinmay.botdesk.web.DashboardServer.hostAllowed(null, ok), "missing host");
    }
}
