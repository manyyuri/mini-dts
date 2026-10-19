package com.minioms.common;

/** 领域异常：message 面向用户可读（任务失败原因直接展示）。 */
public class OmsException extends RuntimeException {
    public OmsException(String message) { super(message); }
    public OmsException(String message, Throwable cause) { super(message, cause); }
}
