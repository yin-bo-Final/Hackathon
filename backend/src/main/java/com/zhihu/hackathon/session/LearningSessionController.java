package com.zhihu.hackathon.session;

import com.zhihu.hackathon.auth.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/learning-sessions")
public class LearningSessionController {
  private final CurrentUserProvider users;
  private final CsrfTokens csrf;
  private final LearningSessionService service;
  public LearningSessionController(CurrentUserProvider users,CsrfTokens csrf,LearningSessionService service) {
    this.users=users;this.csrf=csrf;this.service=service;
  }
  // Jackson 会把 JSON 数字/布尔静默强转成 String，因此 target 用原始 JsonNode 接收并要求必须是文本。
  public record CreateRequest(JsonNode target) {}
  @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
  public LearningSessionService.Created create(@RequestBody CreateRequest body,HttpServletRequest request) {
    long user=users.currentUserId();csrf.verify(request);
    if(body==null||body.target()==null||!body.target().isTextual())
      throw new SessionException(400,"INVALID_TARGET","学习目标必须是文本。");
    return service.create(user,body.target().textValue(),request.getHeader("Idempotency-Key"));
  }
  @GetMapping("/{sessionId}")
  public SessionStore.Snapshot find(@PathVariable String sessionId) { return service.find(users.currentUserId(),sessionId); }

  @GetMapping("/{sessionId}/questions")
  public QuestionsResponse questions(@PathVariable String sessionId) {
    return service.questions(users.currentUserId() , sessionId);
  }

  public record AnswerRequest(String answer) {}
  @PutMapping("/{sessionId}/answers/{questionId}")
  public AnswerResponse saveAnswer(@PathVariable String sessionId, @PathVariable String questionId,
      @RequestBody AnswerRequest body, HttpServletRequest request) {
    long user=users.currentUserId();
    csrf.verify(request);
    return service.saveAnswer(user, sessionId, questionId, body == null ? null : body.answer());
  }

  @PostMapping("/{sessionId}/complete")
  public CompletionResponse complete(@PathVariable String sessionId, HttpServletRequest request) {
    long user=users.currentUserId();
    csrf.verify(request);
    return service.complete(user, sessionId);
  }

  @GetMapping("/{sessionId}/nodes/{nodeId}/resources")
  public ResourcesResponse resources(@PathVariable String sessionId, @PathVariable String nodeId) {
    return service.resources(users.currentUserId(), sessionId, nodeId);
  }
}
