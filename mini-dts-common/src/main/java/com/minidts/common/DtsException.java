package com.minidts.common;

/** 领域异常：message 面向用户可读（任务失败原因直接展示）。 */
public class DtsException extends RuntimeException {
    public DtsException(String message) { super(message); }
    public DtsException(String message, Throwable cause) { super(message, cause); }
}
