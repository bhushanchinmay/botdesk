package io.github.bhushanchinmay.botdesk.agent.tool;

import io.github.bhushanchinmay.botdesk.agent.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The set of tools an agent may use, and their definitions in the OpenAI function-calling format. */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> tools) {
        for (Tool t : tools) {
            if (this.tools.putIfAbsent(t.name(), t) != null) {
                throw new IllegalArgumentException("Two tools are named '" + t.name() + "'");
            }
        }
    }

    public static ToolRegistry builtIn() { return new ToolRegistry(BuiltInTools.all()); }

    /** Returns a new registry with one more tool. */
    public ToolRegistry with(Tool extra) {
        List<Tool> all = new ArrayList<>(tools.values());
        all.add(extra);
        return new ToolRegistry(all);
    }

    public Optional<Tool> find(String name) { return Optional.ofNullable(tools.get(name)); }

    public List<String> names() { return List.copyOf(tools.keySet()); }

    public List<Map<String, Object>> definitions(Workspace workspace) {
        return tools.values().stream().map(t -> definition(t.name(), t.description(workspace), t.parameters())).toList();
    }

    public static Map<String, Object> definition(String name, String description, Map<String, String> params) {
        Map<String, Object> props = new LinkedHashMap<>();
        params.forEach((k, v) -> props.put(k, Map.of("type", "string", "description", v)));
        return Map.of("type", "function", "function", Map.of(
                "name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", props, "required", List.copyOf(params.keySet()))));
    }
}
