package io.github.bhushanchinmay.botdesk.quality;

import java.util.ArrayList;
import java.util.List;

/** Runs every gate and collects all problems, so the engineer can fix them in one pass. */
public final class QualityGates {

    private final List<Gate> gates;

    public QualityGates(List<Gate> gates) {
        this.gates = List.copyOf(gates);
    }

    /** The rules botdesk ships with. Order only affects the order of the feedback. */
    public static QualityGates defaults() {
        return new QualityGates(List.of(
                new BuiltInGates.ChecksPassGate(),
                new BuiltInGates.ReadOnlyGate(),
                new BuiltInGates.ChangeRequiredGate(),
                new BuiltInGates.ProtectedPathGate(),
                new BuiltInGates.TestDisciplineGate(),
                new BuiltInGates.SecretScanGate()));
    }

    /** Returns a new instance with one more gate; handy for project-specific rules and tests. */
    public QualityGates with(Gate extra) {
        List<Gate> all = new ArrayList<>(gates);
        all.add(extra);
        return new QualityGates(all);
    }

    public List<String> check(GateInput input) {
        List<String> problems = new ArrayList<>();
        for (Gate gate : gates) problems.addAll(gate.check(input));
        return problems;
    }

    public List<String> names() { return gates.stream().map(Gate::name).toList(); }
}
