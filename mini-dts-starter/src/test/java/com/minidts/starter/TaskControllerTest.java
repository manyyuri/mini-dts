package com.minidts.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minidts.cm.MigrationEngine;
import com.minidts.cm.SchemaInit;
import com.minidts.cm.TaskController;
import com.minidts.cm.TaskMetadata;
import com.minidts.cm.TaskRepository;
import com.minidts.cm.TaskState;
import com.minidts.cm.VerifyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

/** REST 接口冒烟：真实 H2 元数据库 + 真实 controller + stub engine（engine 的 MySQL 逻辑由 IT 覆盖）。 */
class TaskControllerTest {

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:rest-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        SchemaInit.init(jdbc);
        TaskRepository tasks = new TaskRepository(jdbc);
        VerifyReportRepository reports = new VerifyReportRepository(jdbc);
        MigrationEngine stub = new MigrationEngine() {
            @Override
            public String createTask(TaskCreateRequest r) {
                String id = "t1";
                tasks.insert(new TaskMetadata(id,
                        r.sourceUrl(), "u", "p", r.targetUrl(), "u", "p",
                        r.schema(), r.tables() == null ? List.of() : r.tables(),
                        r.chunkSize(), r.parallelism(), r.rowsPerSecond(),
                        TaskState.INIT, false, null, null, "2026-01-01T00:00", "2026-01-01T00:00"));
                return id;
            }

            @Override
            public void start(String taskId) {
                tasks.updateState(taskId, TaskState.RUNNING_FULL, false, null);
            }

            @Override public void pause(String taskId) { tasks.updateState(taskId, TaskState.PAUSED, false, null); }
            @Override public void resume(String taskId) { tasks.updateState(taskId, TaskState.RUNNING_FULL, false, null); }
            @Override public void finish(String taskId) { tasks.updateState(taskId, TaskState.FINISHED, true, null); }
            @Override public Map<String, Object> verify(String taskId) { return Map.of("allEqual", true); }
            @Override public Map<String, Object> metrics(String taskId) { return Map.of("state", "INIT"); }
        };
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(tasks, stub, reports)).build();
    }

    @Test
    void createStartPauseGetLifecycle() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                "sourceUrl", "jdbc:mysql://src:3306/shop", "targetUrl", "jdbc:mysql://tgt:3306/shop",
                "schema", "shop", "tables", List.of("orders")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("t1"));

        mvc.perform(post("/api/tasks/t1/start")).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING_FULL"))
                .andExpect(jsonPath("$.source").exists())
                .andExpect(jsonPath("$.sourcePassword").doesNotExist());

        mvc.perform(post("/api/tasks/t1/pause")).andExpect(status().isOk());
        mvc.perform(get("/api/tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("PAUSED"));

        mvc.perform(get("/api/tasks/missing")).andExpect(status().isNotFound());
    }
}
