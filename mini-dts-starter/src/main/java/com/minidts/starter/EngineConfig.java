package com.minidts.starter;

import com.minidts.cm.FullCheckpointRepository;
import com.minidts.cm.MigrationEngine;
import com.minidts.cm.SchemaInit;
import com.minidts.cm.TaskRepository;
import com.minidts.cm.VerifyReportRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class EngineConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SchemaInit.init(jdbc);
        return jdbc;
    }

    @Bean
    public TaskRepository taskRepository(JdbcTemplate jdbc) {
        return new TaskRepository(jdbc);
    }

    @Bean
    public FullCheckpointRepository fullCheckpointRepository(JdbcTemplate jdbc) {
        return new FullCheckpointRepository(jdbc);
    }

    @Bean
    public VerifyReportRepository verifyReportRepository(JdbcTemplate jdbc) {
        return new VerifyReportRepository(jdbc);
    }

    @Bean
    public MigrationEngine migrationEngine(TaskRepository tasks,
                                           FullCheckpointRepository checkpoints,
                                           VerifyReportRepository verifyReports) {
        return new MigrationEngineImpl(tasks, checkpoints, verifyReports);
    }
}
