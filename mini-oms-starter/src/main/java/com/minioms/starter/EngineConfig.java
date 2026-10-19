package com.minioms.starter;

import com.minioms.cm.SchemaInit;
import com.minioms.cm.TaskLeaseManager;
import com.minioms.cm.TaskRepository;
import com.minioms.cm.VerifyReportRepository;
import com.minioms.common.GlobalRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(EngineProperties.class)
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
    public VerifyReportRepository verifyReportRepository(JdbcTemplate jdbc) {
        return new VerifyReportRepository(jdbc);
    }

    @Bean
    public TaskLeaseManager taskLeaseManager(JdbcTemplate jdbc, EngineProperties props) {
        return new TaskLeaseManager(jdbc, props.nodeId(), props.leaseTtl());
    }

    @Bean
    public GlobalRateLimiter globalRateLimiter(EngineProperties props) {
        return new GlobalRateLimiter(props.globalRowsPerSecond());
    }

    @Bean
    public MigrationEngineImpl migrationEngine(TaskRepository tasks, VerifyReportRepository verifyReports,
                                               TaskLeaseManager leases, GlobalRateLimiter limiter,
                                               EngineProperties props) {
        return new MigrationEngineImpl(tasks, verifyReports, leases, limiter, props);
    }

    @Bean
    public TaskRecovery taskRecovery(MigrationEngineImpl engine, TaskRepository tasks, EngineProperties props) {
        return new TaskRecovery(engine, tasks, props);
    }
}
