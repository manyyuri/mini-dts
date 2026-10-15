package com.minidts.common;

/**
 * 数据面 → 控制面回调端口（starter 装配时实现；full/store 模块不依赖 Spring）。
 */
public interface TaskStateNotifier {

    /** 全量分片全部完成。 */
    void onFullDone(String taskId);

    /** 数据面失败，任务转 ERROR。 */
    void onError(String taskId, String reason);
}
