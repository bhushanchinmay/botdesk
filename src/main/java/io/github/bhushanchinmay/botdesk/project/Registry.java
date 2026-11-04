package io.github.bhushanchinmay.botdesk.project;

import io.github.bhushanchinmay.botdesk.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/** Loads attached projects (projects/*.json) and playbooks (playbooks/*.json). */
public final class Registry {

    private final Map<String, Project> projects;
    private final Map<String, Playbook> playbooks;

    public Registry(Map<String, Project> projects, Map<String, Playbook> playbooks) {
        this.projects = new TreeMap<>(projects);
        this.playbooks = new TreeMap<>(playbooks);
    }

    public static Registry load(Path projectsDir, Path playbooksDir) {
        Map<String, Project> projects = new TreeMap<>();
        readAll(projectsDir, m -> Project.fromMap(m, projectsDir)).forEach(p -> projects.put(p.name(), p));
        Map<String, Playbook> playbooks = new TreeMap<>();
        readAll(playbooksDir, Playbook::fromMap).forEach(p -> playbooks.put(p.kind(), p));
        return new Registry(projects, playbooks);
    }

    private static <T> Stream<T> readAll(Path dir, Function<Map<String, Object>, T> parse) {
        if (!Files.isDirectory(dir)) return Stream.empty();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().map(file -> {
                try {
                    return parse.apply(Json.parseObject(Files.readString(file)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Bad file " + file + ": " + e.getMessage(), e);
                }
            }).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Project> project(String name) { return Optional.ofNullable(projects.get(name)); }

    public Optional<Playbook> playbook(String kind) { return Optional.ofNullable(playbooks.get(kind)); }

    public Collection<Project> projects() { return projects.values(); }

    public Collection<Playbook> playbooks() { return playbooks.values(); }
}
