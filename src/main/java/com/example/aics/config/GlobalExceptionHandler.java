package com.example.aics.config;

import com.example.aics.agent.AgentAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 全局异常处理（W25-S6，W27 修订）：
 * - 参数类异常（IllegalArgumentException / NumberFormatException / 缺参）→ 400，带回显原因；
 * - 路径不存在（NoResourceFoundException）→ 404，不再被吞成 500（修复 W27：后台任何错路径/漏参数
 *   都会显示"服务器开小差了"，误导排障与监控）；
 * - 方法不支持（GET 调 POST 接口等）→ 405；
 * - 其他未捕获异常 → 500，只记日志回通用文案，不把内部细节（SQL/类名等）泄露给客户端（W25-B3 同理）。
 *
 * 注意：ImageController 自带 IllegalArgumentException 局部 handler，Spring 局部优先，不受影响。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 账号名是跨角色的全局唯一资源，重名属于冲突而非服务器故障。 */
    @ExceptionHandler(AgentAccountService.AccountAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> accountAlreadyExists(
            AgentAccountService.AccountAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("ok", false, "error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, String>> badNumber(NumberFormatException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "参数格式不正确（需要数字）"));
    }

    /** 主密钥缺失/错误属于部署配置问题，明确返回 503，且不泄露密钥内容。 */
    @ExceptionHandler(SecretCryptoService.SecretConfigurationException.class)
    public ResponseEntity<Map<String, String>> secretConfiguration(
            SecretCryptoService.SecretConfigurationException e) {
        log.warn("密钥存储配置不可用: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(LoginRateLimiter.LoginProtectionUnavailableException.class)
    public ResponseEntity<Map<String, String>> loginProtectionUnavailable(
            LoginRateLimiter.LoginProtectionUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }

    /** 必填参数缺失 → 400 并回显缺哪个参数（W27：原被吞成 500，无法定位） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> missingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "缺少必填参数：" + e.getParameterName()));
    }

    /** 路径不存在 → 404（W27：原被吞成 500，导致所有不存在的接口都报"服务器开小差了"） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "接口不存在：" + e.getResourcePath()));
    }

    /** 请求方法不支持（如 GET 调 POST 接口）→ 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "请求方法不支持：" + e.getMethod()));
    }

    /** 业务层主动返回的 403/404 等状态，保留原状态与安全文案，不被兜底处理成 500。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException e) {
        String reason = e.getReason() == null || e.getReason().isBlank() ? "操作不允许" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(500).body(Map.of("error", "服务器开小差了，请稍后再试"));
    }
}
