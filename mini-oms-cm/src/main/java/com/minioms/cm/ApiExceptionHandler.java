package com.minioms.cm;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求体解析失败时给出「能照着改」的错误，而不是 Spring 那句光秃秃的
 * {@code {"status":400,"error":"Bad Request"}}。
 *
 * 真实踩过的场景：多行 JSON 里少了一个引号 / 换行落在了字符串里，
 * Jackson 报 {@code Illegal unquoted character ((CTRL-CHAR, code 10))}，
 * 但接口调用方只看到 400 —— 完全不知道错在哪一行、该传什么字段。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "请求体不是合法 JSON：" + rootMessage(e));
        body.put("hint", "常见原因：JSON 里有未转义的换行/引号，或直接用了多行文本没加引号。"
                + "建议把 JSON 写进文件，用 curl --data-binary @payload.json 发送");
        body.put("expectedFields", List.of(
                "sourceUrl", "sourceUser", "sourcePassword",
                "targetUrl", "targetUser", "targetPassword",
                "schema", "tables[]", "chunkSize", "parallelism", "rowsPerSecond"));
        return ResponseEntity.badRequest().body(body);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}
