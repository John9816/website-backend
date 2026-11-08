package com.example.website;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Source-level guard for package ownership and explicit, acyclic module dependencies. */
class ModuleBoundaryTests {
    private static final Path ROOT = Paths.get("src/main/java/com/example/website");
    private static final Pattern REFERENCE = Pattern.compile("com\\.example\\.website\\.module\\.([a-z]+)\\.([\\w.]+)");

    @Test
    void modulesHaveExplicitAcyclicDependenciesAndRespectLayerBoundaries() throws Exception {
        Map<String, Set<String>> allowed = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/contracts/module-dependencies.json"),
                new TypeReference<Map<String, Set<String>>>() {});
        Map<String, Set<String>> edges = new TreeMap<>();
        List<String> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> paths = Files.walk(ROOT)) {
            files = paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        for (Path file : files) {
            String relative = ROOT.relativize(file).toString().replace('\\', '/');
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (!relative.startsWith("module/")) {
                assertFalse(relative.matches("(controller|dto|entity|repository|service)/.*"), relative);
                if (relative.startsWith("shared/")) assertFalse(REFERENCE.matcher(source).find(), relative);
                continue;
            }
            String module = relative.split("/")[1];
            assertTrue(allowed.containsKey(module), "Declare new module explicitly: " + relative);
            edges.computeIfAbsent(module, key -> new TreeSet<>());
            Matcher refs = REFERENCE.matcher(source);
            while (refs.find()) {
                String target = refs.group(1), layer = refs.group(2);
                if (!module.equals(target)) {
                    edges.get(module).add(target);
                    if (!allowed.get(module).contains(target)) violations.add(relative + " -> " + target);
                    // Administrative aggregates and membership's locked user lookup are explicit exceptions.
                    if (layer.startsWith("infrastructure.persistence.")
                            && !relative.equals("module/administration/application/AdminUserService.java")
                            && !relative.equals("module/administration/application/AdminUserDataService.java")
                            && !relative.equals("module/membership/application/MembershipService.java")) {
                        violations.add(relative + " directly accesses foreign persistence " + target);
                    }
                }
                if (relative.contains("/domain/") && (layer.startsWith("application.")
                        || layer.startsWith("api.") || layer.startsWith("infrastructure."))) {
                    violations.add(relative + " reverses domain dependency: " + refs.group());
                }
                if (relative.contains("/infrastructure/persistence/")
                        && (layer.startsWith("application.") || layer.startsWith("api.controller."))) {
                    violations.add(relative + " reverses persistence dependency: " + refs.group());
                }
                if (relative.contains("/api/controller/") && layer.startsWith("infrastructure.persistence.")) {
                    violations.add(relative + " bypasses application service: " + refs.group());
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
        for (String module : edges.keySet()) checkCycles(module, edges, new LinkedHashSet<>());
    }

    private static void checkCycles(String current, Map<String, Set<String>> edges, Set<String> path) {
        assertTrue(path.add(current), "Module cycle: " + path + " -> " + current);
        for (String target : edges.getOrDefault(current, Collections.emptySet())) checkCycles(target, edges, path);
        path.remove(current);
    }
}
