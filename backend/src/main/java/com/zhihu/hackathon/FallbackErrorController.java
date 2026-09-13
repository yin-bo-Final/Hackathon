package com.zhihu.hackathon;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/** 统一兜底错误响应：未匹配路由等容器级错误不再返回 Spring 默认的 timestamp/path 调试结构。 */
@Controller
public class FallbackErrorController implements ErrorController {
  @RequestMapping("/error")
  public ResponseEntity<Map<String, Map<String, String>>> error(HttpServletRequest request) {
    Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int status = value instanceof Integer code ? code : 500;
    String code = switch (status) {
      case 400 -> "BAD_REQUEST";
      case 401 -> "UNAUTHORIZED";
      case 403 -> "FORBIDDEN";
      case 404 -> "NOT_FOUND";
      case 405 -> "METHOD_NOT_ALLOWED";
      case 429 -> "RATE_LIMITED";
      default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
    };
    String message = switch (status) {
      case 400 -> "请求格式不正确。";
      case 401 -> "请先登录。";
      case 403 -> "没有访问该资源的权限。";
      case 404 -> "接口不存在。";
      case 405 -> "请求方法不被允许。";
      case 429 -> "请求过于频繁，请稍后重试。";
      default -> status >= 500 ? "服务暂时不可用，请重试。" : "请求无法处理。";
    };
    return ResponseEntity.status(status).header("Cache-Control", "no-store")
        .body(Map.of("error", Map.of("code", code, "message", message)));
  }
}
