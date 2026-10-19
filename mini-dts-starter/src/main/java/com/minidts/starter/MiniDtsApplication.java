package com.minidts.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 组件扫描范围必须是 com.minidts（不是本包）—— 控制面 REST 在 mini-dts-cm 里，
 * 只扫 starter 包会导致 /api/tasks 全部 404。
 */
@SpringBootApplication(scanBasePackages = "com.minidts")
public class MiniDtsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniDtsApplication.class, args);
    }
}
