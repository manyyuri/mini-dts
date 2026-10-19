package com.minioms.cm;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskRepository tasks;
    private final MigrationEngine engine;
    private final VerifyReportRepository verifyReports;

    public TaskController(TaskRepository tasks, MigrationEngine engine, VerifyReportRepository verifyReports) {
        this.tasks = tasks;
        this.engine = engine;
        this.verifyReports = verifyReports;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody MigrationEngine.TaskCreateRequest req) {
        try {
            String id = engine.createTask(req);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return tasks.findAll().stream().map(TaskController::view).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return tasks.find(id)
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(view(t)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "任务不存在: " + id)));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id) {
        return act(id, () -> engine.start(id));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable String id) {
        return act(id, () -> engine.pause(id));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable String id) {
        return act(id, () -> engine.resume(id));
    }

    @PostMapping("/{id}/finish")
    public ResponseEntity<?> finish(@PathVariable String id) {
        return act(id, () -> engine.finish(id));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<?> verify(@PathVariable String id) {
        return query(id, () -> engine.verify(id));
    }

    @GetMapping("/{id}/metrics")
    public ResponseEntity<?> metrics(@PathVariable String id) {
        return query(id, () -> engine.metrics(id));
    }

    /** 视图（不回显密码）。 */
    static Map<String, Object> view(TaskMetadata t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("source", t.sourceUrl());
        m.put("target", t.targetUrl());
        m.put("schema", t.schema());
        m.put("tables", t.tables());
        m.put("chunkSize", t.chunkSize());
        m.put("parallelism", t.parallelism());
        m.put("rowsPerSecond", t.rowsPerSecond());
        m.put("state", t.state());
        m.put("fullDone", t.fullDone());
        m.put("consistencyGtid", t.consistencyGtid());
        if (t.error() != null && !t.error().isBlank()) m.put("error", t.error());
        m.put("createdAt", t.createdAt());
        m.put("updatedAt", t.updatedAt());
        return m;
    }

    private ResponseEntity<?> act(String id, Exec body) {
        try {
            body.run();
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> query(String id, java.util.function.Supplier<Object> body) {
        try {
            return ResponseEntity.ok(body.get());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface Exec {
        void run() throws Exception;
    }

    static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }
}
