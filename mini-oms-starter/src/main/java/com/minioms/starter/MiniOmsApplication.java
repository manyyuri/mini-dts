package com.minioms.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 组件扫描范围必须是 com.minioms（不是本包）—— 控制面 REST 在 mini-oms-cm 里，
 * 只扫 starter 包会导致 /api/tasks 全部 404。
 */
@SpringBootApplication(scanBasePackages = "com.minioms")
public class MiniOmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniOmsApplication.class, args);
    }
}
