package io.github.bhushanchinmay.botdesk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Tiny test runner so the project needs no JUnit download. Runs every public static
 * void method named test* in the listed classes. Exit code 1 if anything fails.
 */
public final class TestRunner {

    public static void main(String[] args) throws Exception {
        List<Class<?>> suites = List.of(JsonTest.class, TaskStateTest.class, WorkspaceTest.class, GatesTest.class,
                OpenAiClientTest.class, PipelineTest.class, ChatServiceTest.class, SetupCheckTest.class, AgentTest.class,
                ConfigTest.class);
        int passed = 0;
        int failed = 0;
        for (Class<?> suite : suites) {
            Method[] methods = suite.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers())) continue;
                try {
                    m.invoke(null);
                    passed++;
                    System.out.println("PASS " + suite.getSimpleName() + "." + m.getName());
                } catch (InvocationTargetException e) {
                    failed++;
                    System.out.println("FAIL " + suite.getSimpleName() + "." + m.getName() + ": " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                }
            }
        }
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void eq(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void throwsAny(Runnable r, String message) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return;
        }
        throw new AssertionError(message + ": expected an exception");
    }
}
