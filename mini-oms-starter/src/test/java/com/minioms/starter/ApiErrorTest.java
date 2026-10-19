package com.minioms.starter;

import com.minioms.cm.ApiExceptionHandler;
import com.minioms.cm.MigrationEngine;
import com.minioms.cm.SchemaInit;
import com.minioms.cm.TaskController;
import com.minioms.cm.TaskRepository;
import com.minioms.cm.VerifyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 建任务接口的「错误可读性」：坏 JSON 不能只回一个 400，要告诉调用方错在哪、该传什么。
 */
class ApiErrorTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:apierr-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaInit.init(jdbc);
        MigrationEngine stub = new MigrationEngine() {
            public String createTask(TaskCreateRequest r) {
                return "t1";
            }

            public void start(String id) {
            }

            public void pause(String id) {
            }

            public void resume(String id) {
            }

            public void finish(String id) {
            }

            public boolean reattach(String id) {
                return false;
            }

            public Map<String, Object> verify(String id) {
                return Map.of();
            }

            public Map<String, Object> metrics(String id) {
                return Map.of();
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(new TaskRepository(jdbc), stub,
                        new VerifyReportRepository(jdbc)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void malformedJsonGetsActionableHint() throws Exception {
        // 典型现场：JSON 里出现未转义的换行（Jackson 报 CTRL-CHAR code 10）
        String broken = "{\"sourceUrl\":\"jdbc:mysql://h:3306/shop\"\n  \"schema\":\"shop\"}";
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(broken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不是合法 JSON")))
                .andExpect(jsonPath("$.hint").value(org.hamcrest.Matchers.containsString("--data-binary")))
                .andExpect(jsonPath("$.expectedFields[0]").value("sourceUrl"));
    }

    @Test
    void missingBodyAlsoGetsHint() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不是合法 JSON")));
    }
}
