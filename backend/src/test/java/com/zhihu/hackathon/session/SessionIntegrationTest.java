package com.zhihu.hackathon.session;

import com.zhihu.hackathon.auth.SessionAuthentication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.util.List;
import java.time.Duration;
import static com.zhihu.hackathon.session.Generation.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=", "spring.profiles.active=integration"})
@AutoConfigureMockMvc
class SessionIntegrationTest {
  static final String DB=temporaryDb();
  @DynamicPropertySource static void db(DynamicPropertyRegistry r) { r.add("spring.datasource.url",()->DB); }
  static String temporaryDb() { try { return "jdbc:sqlite:"+Files.createTempFile("sessions-", ".db"); } catch(Exception e) { throw new IllegalStateException(e); } }
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired SessionStore store;
  @MockitoBean SiliconFlowGenerationClient model;
  @MockitoBean ResourceSearch search;
  MockHttpSession session;
  String csrf;
  @BeforeEach void user() throws Exception {
    // Each test starts a fresh per-user quota without changing admission policy.
    seedUser(999,"archived-test-fixtures");
    jdbc.update("UPDATE learning_sessions SET user_id=999 WHERE user_id=1");
    jdbc.update("DELETE FROM session_creation_events WHERE user_id=1");
    seedUser(1, "test-one");
    seedUser(2, "test-two");
    session=new MockHttpSession();session.setAttribute(SessionAuthentication.USER_ID,1L);
    var me=mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk()).andReturn();
    csrf=json.readTree(me.getResponse().getContentAsString()).path("csrfToken").asText();
    when(model.generateGraph(anyString())).thenReturn(new Graph(List.of(new Node("a","矩阵运算","理解计算")),List.of(new Edge("a","target")), "目标的具体介绍"));
    when(search.search(anyString(),anyInt())).thenReturn(List.of(new Resource("资料","https://www.zhihu.com/question/1",null,null,null,"2024-03-10")));
    when(model.generateQuestions(anyList())).thenAnswer(invocation -> {
      List<SavedNode> ns=invocation.getArgument(0);
      return ns.stream().map(n -> new Question(n.id(),"你了解"+n.name()+"吗？","用途")).toList();
    });
  }
  @Test void deletingRunningHistoryDoesNotBypassUserTaskLimit() throws Exception {
    var started=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
    when(model.generateGraph(anyString())).thenAnswer(call->{
      started.countDown();release.await();
      return new Graph(List.of(new Node("a","矩阵运算","理解计算")),List.of(new Edge("a","target")),"目标说明");
    });
    long first=0,second=0;
    try {
      first=create();second=create();assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      mvc.perform(delete("/api/v1/learning-sessions/"+first).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isOk());
      mvc.perform(post("/api/v1/learning-sessions").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"target\":\"不能绕过\"}"))
          .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("USER_TASK_LIMIT_REACHED"));
    } finally { release.countDown(); }
    waitFor(second,"READY");
  }
  @Test void concurrentIdempotentRequestsCreateExactlyOneJob() throws Exception {
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var gate=new java.util.concurrent.CountDownLatch(1);
      var tasks=new java.util.ArrayList<java.util.concurrent.Future<String>>();
      for(int i=0;i<4;i++) tasks.add(pool.submit(()->{
        gate.await();
        var response=mvc.perform(post("/api/v1/learning-sessions").session(session)
            .header("X-CSRF-Token",csrf).header("Idempotency-Key","integration-request-123")
            .contentType("application/json").content("{\"target\":\"Transformer\"}"))
            .andExpect(status().isAccepted()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("sessionId").asText();
      }));
      gate.countDown();var ids=new java.util.HashSet<String>();
      for(var task:tasks) ids.add(task.get(5,java.util.concurrent.TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);waitFor(Long.parseLong(ids.iterator().next()),"READY");
      verify(model,times(1)).generateGraph("Transformer");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_creation_events WHERE user_id=1",Integer.class)).isEqualTo(1);
      mvc.perform(post("/api/v1/learning-sessions").session(session).header("X-CSRF-Token",csrf)
          .header("Idempotency-Key","integration-request-123").contentType("application/json").content("{\"target\":\"不同目标\"}"))
          .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }
  }
  @Test void creationLimitsReturnActionableErrorsAndDeleteReleasesHistorySlot() throws Exception {
    for(int i=0;i<20;i++) store.create(1,"已存在");
    mvc.perform(post("/api/v1/learning-sessions").session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"target\":\"新目标\",\"userId\":2}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SESSION_LIMIT_REACHED"));
    long id=jdbc.queryForObject("SELECT MIN(id) FROM learning_sessions WHERE user_id=1",Long.class);
    mvc.perform(delete("/api/v1/learning-sessions/"+id).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isOk());
    long created=create();waitFor(created,"READY");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_sessions WHERE user_id=1",Integer.class)).isEqualTo(20);
  }
  @Test void creationRateLimitReturnsRetryAfterWithoutCreatingRecord() throws Exception {
    for(int i=0;i<10;i++) store.createWithinLimits(1,"刚创建");
    mvc.perform(post("/api/v1/learning-sessions").session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"target\":\"超出频率\"}"))
        .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.error.code").value("USER_CREATE_RATE_LIMITED"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_sessions WHERE user_id=1",Integer.class)).isEqualTo(10);
  }
  @Test void hardDeleteRemovesAllChildrenAndRejectsUnauthorizedRequests() throws Exception {
    long id=create(); waitFor(id,"READY");
    long q=jdbc.queryForObject("SELECT q.id FROM assessment_questions q JOIN knowledge_nodes n ON n.id=q.node_id WHERE n.session_id=?",Long.class,id);
    answer(id,q,"HEARD_OF"); complete(id);
    long node=nodeId(id,"矩阵运算");
    var other=new MockHttpSession();other.setAttribute(SessionAuthentication.USER_ID,2L);
    var me=mvc.perform(get("/api/v1/auth/me").session(other)).andReturn();
    String otherCsrf=json.readTree(me.getResponse().getContentAsString()).path("csrfToken").asText();
    mvc.perform(delete("/api/v1/learning-sessions/"+id).session(other).header("X-CSRF-Token",otherCsrf)).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/learning-sessions/"+id).session(session)).andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/learning-sessions/"+id)).andExpect(status().isUnauthorized());
    long retained=store.create(2,"保留另一用户");
    mvc.perform(delete("/api/v1/learning-sessions/"+id).session(session).header("X-CSRF-Token",csrf))
        .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
    for(String table:List.of("learning_sessions","knowledge_nodes","knowledge_edges"))
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+(table.equals("learning_sessions")?"id":"session_id")+"=?",Integer.class,id)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_answers WHERE question_id=?",Integer.class,q)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_questions WHERE id=?",Integer.class,q)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM node_resources WHERE node_id=?",Integer.class,node)).isZero();
    assertThat(store.findOwned(2,retained).target()).isEqualTo("保留另一用户");
    mvc.perform(get("/api/v1/learning-sessions/"+id).session(session)).andExpect(status().isNotFound());
    // Reuse a deleted SQLite node ID: a late search must not write into its new owner.
    jdbc.update("INSERT INTO knowledge_nodes(id,session_id,name,description,level) VALUES (?,?,?,'',0)",node,retained,"复用节点");
    store.saveResources(id,node,List.of(new Resource("迟到资料","https://www.zhihu.com/question/1",null,null,null)),false);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM node_resources WHERE node_id=?",Integer.class,node)).isZero();
    assertThatThrownBy(() -> store.ready(id,List.of(new Question(Long.toString(node),"迟到问题","提示")))).isInstanceOf(SessionException.class);
  }
  @Test void deleteRollsBackAllChangesWhenAChildCannotBeRemoved() throws Exception {
    long id=create(); waitFor(id,"READY");
    long q=jdbc.queryForObject("SELECT q.id FROM assessment_questions q JOIN knowledge_nodes n ON n.id=q.node_id WHERE n.session_id=?",Long.class,id);
    answer(id,q,"HEARD_OF"); complete(id);
    jdbc.execute("CREATE TRIGGER block_test_delete BEFORE DELETE ON knowledge_nodes WHEN OLD.session_id="+id+" BEGIN SELECT RAISE(ABORT,'test rollback'); END");
    try {
      mvc.perform(delete("/api/v1/learning-sessions/"+id).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isInternalServerError());
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_answers WHERE question_id=?",Integer.class,q)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM node_resources r JOIN knowledge_nodes n ON n.id=r.node_id WHERE n.session_id=?",Integer.class,id)).isPositive();
      assertThat(store.findOwned(1,id).status()).isEqualTo("COMPLETED");
    } finally { jdbc.execute("DROP TRIGGER block_test_delete"); }
  }
  @Test void deletingDuringGraphGenerationPreventsLateGraphInsertion() throws Exception {
    long id=store.create(1,"删除生成中");
    mvc.perform(delete("/api/v1/learning-sessions/"+id).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isOk());
    var graph=new GraphValidator().validate("删除生成中",new Graph(List.of(new Node("a","基础","说明")),List.of(new Edge("a","target")),"目标"));
    assertThatThrownBy(() -> store.saveGraph(id,"删除生成中",graph)).isInstanceOf(SessionException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_nodes WHERE session_id=?",Integer.class,id)).isZero();
  }
  @Test void snowflakeSessionsAreStringsAndLegacyIdsRemainReadable() throws Exception {
    seedUser(95,"snowflake-owner");
    var owner=new MockHttpSession();owner.setAttribute(SessionAuthentication.USER_ID,95L);
    jdbc.update("INSERT INTO learning_sessions(id,user_id,target_name,status,created_at,updated_at) VALUES (90,95,'旧寻路','GENERATING_GRAPH','now','now')");
    mvc.perform(get("/api/v1/learning-sessions/90").session(owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("90"));
    try (var executor=java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Long>>();
      for(int i=0;i<24;i++) tasks.add(() -> store.create(95,"新寻路"));
      var ids=new java.util.HashSet<Long>();
      for(var future:executor.invokeAll(tasks)) {
        long id=future.get();
        assertThat(id).isGreaterThan(9_007_199_254_740_991L);
        assertThat(ids.add(id)).isTrue();
        mvc.perform(get("/api/v1/learning-sessions/"+id).session(owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(Long.toString(id)));
      }
    }
  }
  @Test void historyIsOwnedPaginatedAndNewestFirst() throws Exception {
    seedUser(91, "history-owner");
    seedUser(92, "history-other");
    var owner=new MockHttpSession();owner.setAttribute(SessionAuthentication.USER_ID,91L);
    for(int i=0;i<21;i++) store.create(91,"历史目标"+i);
    store.create(92,"不应看到的目标");
    mvc.perform(get("/api/v1/learning-sessions").session(owner).param("userId","92"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.total").value(21)).andExpect(jsonPath("$.items.length()").value(20))
        .andExpect(jsonPath("$.items[0].target").value("历史目标20"));
    mvc.perform(get("/api/v1/learning-sessions").session(owner).param("page","2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].target").value("历史目标0"));
    mvc.perform(get("/api/v1/learning-sessions").session(owner).param("page","0"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/learning-sessions")).andExpect(status().isUnauthorized());
  }
  @Test void historyIncludesOnlyItsOwnRootDescription() throws Exception {
    seedUser(94,"history-description");
    var owner=new MockHttpSession();owner.setAttribute(SessionAuthentication.USER_ID,94L);
    long id=store.create(94,"线性代数");
    jdbc.update("INSERT INTO knowledge_nodes(session_id,name,description,level,is_target,resource_status) VALUES (?,?,?,0,1,'NOT_APPLICABLE')", id,"线性代数","研究向量与矩阵的基础知识");
    jdbc.update("INSERT INTO knowledge_nodes(session_id,name,description,level) VALUES (?,?,?,0)", id,"向量","不应作为根节点描述");
    mvc.perform(get("/api/v1/learning-sessions").session(owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].targetDescription").value("研究向量与矩阵的基础知识"));
  }
  @Test void historyReturnsEmptyForNewUser() throws Exception {
    seedUser(93,"history-empty");
    var owner=new MockHttpSession();owner.setAttribute(SessionAuthentication.USER_ID,93L);
    mvc.perform(get("/api/v1/learning-sessions").session(owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.items.length()").value(0));
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"MODEL_REQUEST_TIMEOUT","MODEL_JSON_PARSE_ERROR"})
  void exposesSpecificModelFailureInSession(String code) throws Exception {
    when(model.generateGraph(anyString())).thenThrow(new ModelGenerationException(code));
    long id=create(); waitFor(id,"FAILED");
    mvc.perform(get("/api/v1/learning-sessions/"+id).session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(code));
  }
  @Test void createsReadySessionWithSavedGraphResourcesAndQuestions() throws Exception {
    long id=create();waitFor(id,"READY");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_nodes WHERE session_id=?",Integer.class,id)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_questions q JOIN knowledge_nodes n ON n.id=q.node_id WHERE n.session_id=?",Integer.class,id)).isEqualTo(1);
    var snapshot=store.findOwned(1,id);
    assertThat(snapshot.progress()).isEqualTo(new SessionStore.Progress(0,1));
    var order=inOrder(model);order.verify(model).generateGraph("Transformer");order.verify(model).generateQuestions(anyList());
    verifyNoInteractions(search);
  }
  @Test void isolatesUsersAndRejectsClientUserId() throws Exception {
    long id=create();waitFor(id,"READY");
    var other=new MockHttpSession();other.setAttribute(SessionAuthentication.USER_ID,2L);
    mvc.perform(get("/api/v1/learning-sessions/"+id).session(other).param("userId","1"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    assertThat(jdbc.queryForObject("SELECT user_id FROM learning_sessions WHERE id=?",Long.class,id)).isEqualTo(1);
  }
  @Test void returnsQuestionsInOrderWithFixedOptionsAndSavedAnswer() throws Exception {
    long id=create();waitFor(id,"READY");
    long questionId=jdbc.queryForObject("SELECT q.id FROM assessment_questions q JOIN knowledge_nodes n ON n.id=q.node_id WHERE n.session_id=?",Long.class,id);
    jdbc.update("INSERT INTO assessment_answers(question_id,answer_value,answered_at) VALUES (?, 'VERY_FAMILIAR', 'now')",questionId);

    mvc.perform(get("/api/v1/learning-sessions/"+id+"/questions").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.questions[0].questionId").value(Long.toString(questionId)))
        .andExpect(jsonPath("$.questions[0].nodeName").value("矩阵运算"))
        .andExpect(jsonPath("$.questions[0].answer").value("VERY_FAMILIAR"))
        .andExpect(jsonPath("$.questions[0].options[0].value").value("VERY_FAMILIAR"))
        .andExpect(jsonPath("$.questions[0].options[3].value").value("DONT_KNOW"));
  }
  @Test void returnsMultipleQuestionsBySortOrderAndKeepsUnansweredValueNull() throws Exception {
    when(model.generateGraph(anyString())).thenReturn(new Graph(
        List.of(new Node("a","线性代数","理解向量"),new Node("b","概率论","理解概率")),
        List.of(new Edge("a","target"),new Edge("b","target")), "目标的具体介绍"));
    long id=create();waitFor(id,"READY");
    var questionIds=jdbc.query("SELECT q.id FROM assessment_questions q JOIN knowledge_nodes n ON n.id=q.node_id WHERE n.session_id=? ORDER BY q.id",
        (rs,row)->rs.getLong(1),id);
    jdbc.update("UPDATE assessment_questions SET sort_order=1 WHERE id=?",questionIds.get(0));
    jdbc.update("UPDATE assessment_questions SET sort_order=0 WHERE id=?",questionIds.get(1));

    mvc.perform(get("/api/v1/learning-sessions/"+id+"/questions").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.questions.length()").value(2))
        .andExpect(jsonPath("$.questions[0].questionId").value(Long.toString(questionIds.get(1))))
        .andExpect(jsonPath("$.questions[1].questionId").value(Long.toString(questionIds.get(0))))
        .andExpect(jsonPath("$.questions[0].answer").value(org.hamcrest.Matchers.nullValue()));
  }
  @Test void rejectsQuestionsForAnotherUserOrSessionThatIsNotReady() throws Exception {
    long id=create();waitFor(id,"READY");
    var other=new MockHttpSession();other.setAttribute(SessionAuthentication.USER_ID,2L);
    mvc.perform(get("/api/v1/learning-sessions/"+id+"/questions").session(other))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

    jdbc.update("INSERT INTO learning_sessions(user_id,target_name,status,created_at,updated_at) VALUES (1,'等待中','GENERATING_GRAPH','now','now')");
    long pending=jdbc.queryForObject("SELECT last_insert_rowid()",Long.class);
    mvc.perform(get("/api/v1/learning-sessions/"+pending+"/questions").session(session))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_READY"));
  }
  @Test void allowsCompletedSessionsAndRejectsInvalidIdsOrUnauthenticatedRequests() throws Exception {
    long id=create();waitFor(id,"READY");
    jdbc.update("UPDATE learning_sessions SET status='COMPLETED' WHERE id=?",id);
    mvc.perform(get("/api/v1/learning-sessions/"+id+"/questions").session(session))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/learning-sessions/0/questions").session(session))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    mvc.perform(get("/api/v1/learning-sessions/99999999999999999999/questions").session(session))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    mvc.perform(get("/api/v1/learning-sessions/"+id+"/questions"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
  }
  @Test void returnsAnEmptyQuestionListWhenTargetHasNoPrerequisites() throws Exception {
    when(model.generateGraph(anyString())).thenReturn(new Graph(List.of(),List.of(), "目标的具体介绍"));
    long id=create();waitFor(id,"READY");
    mvc.perform(get("/api/v1/learning-sessions/"+id+"/questions").session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.questions.length()").value(0));
  }
  @Test void savesAnswersUpdatesMasteryAndDoesNotDoubleCountRepeatedSubmissions() throws Exception {
    long id=create();waitFor(id,"READY");
    long questionId=questionId(id);
    String path="/api/v1/learning-sessions/"+id+"/answers/"+questionId;

    mvc.perform(put(path).session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"answer\":\"VERY_FAMILIAR\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.questionId").value(Long.toString(questionId)))
        .andExpect(jsonPath("$.masteryStatus").value("MASTERED"))
        .andExpect(jsonPath("$.answeredCount").value(1))
        .andExpect(jsonPath("$.totalQuestions").value(1));
    mvc.perform(put(path).session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"answer\":\"HEARD_OF\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.masteryStatus").value("TO_LEARN"))
        .andExpect(jsonPath("$.answeredCount").value(1));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_answers WHERE question_id=?",Integer.class,questionId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT mastery_status FROM knowledge_nodes n JOIN assessment_questions q ON q.node_id=n.id WHERE q.id=?",String.class,questionId)).isEqualTo("TO_LEARN");
  }
  @Test void changesToCompletedSessionAnswersRestoreReadyAndClearCompletionTime() throws Exception {
    long id=create();waitFor(id,"READY");
    long questionId=questionId(id);
    jdbc.update("INSERT INTO assessment_answers(question_id,answer_value,answered_at) VALUES (?, 'VERY_FAMILIAR', 'now')",questionId);
    jdbc.update("UPDATE knowledge_nodes SET mastery_status='MASTERED' WHERE id=(SELECT node_id FROM assessment_questions WHERE id=?)",questionId);
    jdbc.update("UPDATE learning_sessions SET status='COMPLETED',completed_at='now' WHERE id=?",id);
    String path="/api/v1/learning-sessions/"+id+"/answers/"+questionId;

    mvc.perform(put(path).session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"answer\":\"DONT_KNOW\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.masteryStatus").value("TO_LEARN"));
    assertThat(jdbc.queryForObject("SELECT status FROM learning_sessions WHERE id=?",String.class,id)).isEqualTo("READY");
    assertThat(jdbc.queryForObject("SELECT completed_at FROM learning_sessions WHERE id=?",String.class,id)).isNull();
  }
  @Test void rejectsInvalidAnswersAndUnauthorizedOrUnavailableAnswerWrites() throws Exception {
    long id=create();waitFor(id,"READY");
    long questionId=questionId(id);
    String path="/api/v1/learning-sessions/"+id+"/answers/"+questionId;
    mvc.perform(put(path).session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"answer\":\"UNKNOWN\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_ANSWER"));
    mvc.perform(put(path).session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_ANSWER"));
    mvc.perform(put(path).session(session).contentType("application/json").content("{\"answer\":\"VERY_FAMILIAR\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    var other=new MockHttpSession();other.setAttribute(SessionAuthentication.USER_ID,2L);
    var otherMe=mvc.perform(get("/api/v1/auth/me").session(other)).andExpect(status().isOk()).andReturn();
    String otherCsrf=json.readTree(otherMe.getResponse().getContentAsString()).path("csrfToken").asText();
    mvc.perform(put(path).session(other).header("X-CSRF-Token",otherCsrf)
        .contentType("application/json").content("{\"answer\":\"VERY_FAMILIAR\"}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    jdbc.update("UPDATE learning_sessions SET status='FAILED' WHERE id=?",id);
    mvc.perform(put(path).session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"answer\":\"VERY_FAMILIAR\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_READY"));
  }
  @Test void completesAnsweredSessionAndPreservesMasteredNodesAndOriginalEdges() throws Exception {
    when(model.generateGraph(anyString())).thenReturn(new Graph(
        List.of(new Node("a","基础","基础说明"),new Node("b","中间","中间说明"),new Node("c","进阶","进阶说明")),
        List.of(new Edge("a","b"),new Edge("b","c"),new Edge("c","target")), "目标的具体介绍"));
    long id=create();waitFor(id,"READY");
    answer(id, questionId(id, "基础"), "HEARD_OF");
    answer(id, questionId(id, "中间"), "VERY_FAMILIAR");
    answer(id, questionId(id, "进阶"), "DONT_KNOW");
    String path="/api/v1/learning-sessions/"+id+"/complete";
    long basicId=nodeId(id, "基础");
    long middleId=nodeId(id, "中间");
    complete(id);

    mvc.perform(post(path).session(session).header("X-CSRF-Token",csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(Long.toString(id)))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.missingCount").value(2))
        .andExpect(jsonPath("$.nodes.length()").value(4))
        .andExpect(jsonPath("$.nodes[0].name").value("基础"))
        .andExpect(jsonPath("$.nodes[0].level").value(0))
        .andExpect(jsonPath("$.nodes[1].name").value("中间"))
        .andExpect(jsonPath("$.nodes[1].level").value(1))
        .andExpect(jsonPath("$.nodes[2].name").value("进阶"))
        .andExpect(jsonPath("$.edges.length()").value(3))
        .andExpect(jsonPath("$.edges[0].from").value(Long.toString(basicId)))
        .andExpect(jsonPath("$.edges[0].to").value(Long.toString(middleId)));
    String completedAt=jdbc.queryForObject("SELECT completed_at FROM learning_sessions WHERE id=?",String.class,id);
    mvc.perform(post(path).session(session).header("X-CSRF-Token",csrf))
        .andExpect(status().isOk()).andExpect(jsonPath("$.missingCount").value(2));
    assertThat(jdbc.queryForObject("SELECT completed_at FROM learning_sessions WHERE id=?",String.class,id)).isEqualTo(completedAt);
  }
  @Test void completesSessionsWithoutPrerequisitesAndRejectsIncompleteOrUnauthorizedRequests() throws Exception {
    long incomplete=create();waitFor(incomplete,"READY");
    String incompletePath="/api/v1/learning-sessions/"+incomplete+"/complete";
    mvc.perform(post(incompletePath).session(session).header("X-CSRF-Token",csrf))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ANSWERS_INCOMPLETE"));
    mvc.perform(post(incompletePath).session(session))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    var other=new MockHttpSession();other.setAttribute(SessionAuthentication.USER_ID,2L);
    var otherMe=mvc.perform(get("/api/v1/auth/me").session(other)).andExpect(status().isOk()).andReturn();
    String otherCsrf=json.readTree(otherMe.getResponse().getContentAsString()).path("csrfToken").asText();
    mvc.perform(post(incompletePath).session(other).header("X-CSRF-Token",otherCsrf))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

    when(model.generateGraph(anyString())).thenReturn(new Graph(List.of(),List.of(), "目标的具体介绍"));
    long direct=create();waitFor(direct,"READY");
    mvc.perform(post("/api/v1/learning-sessions/"+direct+"/complete").session(session).header("X-CSRF-Token",csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.missingCount").value(0))
        .andExpect(jsonPath("$.nodes.length()").value(1))
        .andExpect(jsonPath("$.nodes[0].name").value("Transformer"))
        .andExpect(jsonPath("$.edges.length()").value(0));
  }
  @Test void returnsCachedResourcesForVisibleNodesAndNotApplicableForTarget() throws Exception {
    long id=create();waitFor(id,"READY");
    long questionId=questionId(id);
    answer(id, questionId, "HEARD_OF");
    complete(id);
    long nodeId=nodeId(id, "矩阵运算");
    jdbc.update("UPDATE node_resources SET vote_count=42 WHERE node_id=?",nodeId);

    mvc.perform(get("/api/v1/learning-sessions/"+id+"/nodes/"+nodeId+"/resources").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodeId").value(Long.toString(nodeId)))
        .andExpect(jsonPath("$.nodeName").value("矩阵运算"))
        .andExpect(jsonPath("$.reason").value("理解计算"))
        .andExpect(jsonPath("$.resourceStatus").value("READY"))
        .andExpect(jsonPath("$.resources.length()").value(1))
        .andExpect(jsonPath("$.resources[0].title").value("资料"))
        .andExpect(jsonPath("$.resources[0].url").value("https://www.zhihu.com/question/1"))
        .andExpect(jsonPath("$.resources[0].voteCount").value(42))
        .andExpect(jsonPath("$.resources[0].contentDate").value("2024-03-10"));
    long targetId=jdbc.queryForObject("SELECT id FROM knowledge_nodes WHERE session_id=? AND is_target=1",Long.class,id);
    mvc.perform(get("/api/v1/learning-sessions/"+id+"/nodes/"+targetId+"/resources").session(session))
        .andExpect(jsonPath("$.reason").value("目标的具体介绍"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resourceStatus").value("NOT_APPLICABLE"))
        .andExpect(jsonPath("$.resources.length()").value(0));
  }
  @Test void rejectsResourcesForHiddenNodesIncompleteSessionsAndOtherUsers() throws Exception {
    long id=create();waitFor(id,"READY");
    long nodeId=nodeId(id, "矩阵运算");
    String path="/api/v1/learning-sessions/"+id+"/nodes/"+nodeId+"/resources";
    mvc.perform(get(path))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    mvc.perform(get(path).session(session))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_COMPLETED"));
    answer(id, questionId(id), "VERY_FAMILIAR");
    complete(id);
    mvc.perform(get(path).session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.resourceStatus").value("NOT_APPLICABLE"))
        .andExpect(jsonPath("$.resources.length()").value(0));
    var other=new MockHttpSession();other.setAttribute(SessionAuthentication.USER_ID,2L);
    mvc.perform(get(path).session(other))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }
  @Test void returnsEmptyAndFailedResourceStatusesWithoutInventingResources() throws Exception {
    when(search.search(anyString(),anyInt())).thenReturn(List.of());
    long emptySession=create();waitFor(emptySession,"READY");
    answer(emptySession, questionId(emptySession), "HEARD_OF");
    complete(emptySession);
    long emptyNode=nodeId(emptySession, "矩阵运算");
    mvc.perform(get("/api/v1/learning-sessions/"+emptySession+"/nodes/"+emptyNode+"/resources").session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.resourceStatus").value("EMPTY"))
        .andExpect(jsonPath("$.resources.length()").value(0));

    when(search.search(anyString(),anyInt())).thenThrow(new IllegalStateException("upstream unavailable"));
    long failedSession=create();waitFor(failedSession,"READY");
    answer(failedSession, questionId(failedSession), "HEARD_OF");
    complete(failedSession);
    long failedNode=nodeId(failedSession, "矩阵运算");
    mvc.perform(get("/api/v1/learning-sessions/"+failedSession+"/nodes/"+failedNode+"/resources").session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.resourceStatus").value("FAILED"))
        .andExpect(jsonPath("$.resources.length()").value(0));
  }
  @Test void requiresLoginAndCsrf() throws Exception {
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/learning-sessions").contentType("application/json").content("{\"target\":\"X\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/learning-sessions").session(session).contentType("application/json").content("{\"target\":\"X\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(model,search);
  }
  @Test void invalidTargetsDoNotGenerate() throws Exception {
    mvc.perform(post("/api/v1/learning-sessions").session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"target\":\" \"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_TARGET"));
    verifyNoInteractions(model,search);
  }
  @Test void nonStringTargetsAreRejectedWithoutGenerating() throws Exception {
    // Jackson 会把 JSON 数字/布尔静默强转成 String；这些载荷必须与缺失目标一样被拒绝。
    for (String body : List.of("{\"target\":123}", "{\"target\":true}", "{\"target\":[\"x\"]}", "{}")) {
      mvc.perform(post("/api/v1/learning-sessions").session(session).header("X-CSRF-Token",csrf)
          .contentType("application/json").content(body))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_TARGET"));
    }
    verifyNoInteractions(model,search);
  }
  @Test void numericTextTargetsRemainAccepted() throws Exception {
    mvc.perform(post("/api/v1/learning-sessions").session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"target\":\"123\"}"))
        .andExpect(status().isAccepted());
  }
  @Test void searchFailureBecomesWarningAndStillReady() throws Exception {
    when(search.search(anyString(),anyInt())).thenThrow(new IllegalStateException("private response"));
    long id=create();waitFor(id,"READY");
    answer(id,questionId(id),"HEARD_OF"); complete(id);
    assertThat(store.findOwned(1,id).warnings()).hasSize(1);
    assertThat(store.findOwned(1,id).progress()).isEqualTo(new SessionStore.Progress(1,1));
  }
  @Test void invalidGraphFailsBeforeSavingAnyNodes() throws Exception {
    when(model.generateGraph(anyString())).thenReturn(new Graph(List.of(new Node("a","A","why")),List.of(), "目标的具体介绍"));
    long id=create();waitFor(id,"FAILED");
    assertThat(store.findOwned(1,id).error().code()).isEqualTo("GRAPH_VALIDATION_FAILED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_nodes WHERE session_id=?",Integer.class,id)).isZero();
    verifyNoInteractions(search);
  }
  @Test void missingQuestionsFailWithoutPartialQuestionRows() throws Exception {
    when(model.generateQuestions(anyList())).thenReturn(List.of());
    long id=create();waitFor(id,"FAILED");
    assertThat(store.findOwned(1,id).error().code()).isEqualTo("QUESTION_VALIDATION_FAILED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_questions q JOIN knowledge_nodes n ON n.id=q.node_id WHERE n.session_id=?",Integer.class,id)).isZero();
  }
  @Test void noPrerequisitesSkipsSearchAndQuestionModel() throws Exception {
    when(model.generateGraph(anyString())).thenReturn(new Graph(List.of(),List.of(), "目标的具体介绍"));
    long id=create();waitFor(id,"READY");
    verify(model,never()).generateQuestions(anyList());verifyNoInteractions(search);
    assertThat(store.findOwned(1,id).progress()).isEqualTo(new SessionStore.Progress(0,0));
  }
  @Test void recoveryOnlyFailsUnfinishedSessions() throws Exception {
    long ready=create();waitFor(ready,"READY");
    long unfinished=store.create(1,"Interrupted");store.recoverInterrupted();
    assertThat(store.findOwned(1,unfinished).error().code()).isEqualTo("GENERATION_INTERRUPTED");
    assertThat(store.findOwned(1,ready).status()).isEqualTo("READY");
  }
  @Test void assignsFourResourceLimitsAndOnlySearchesChangedAnswers() throws Exception {
    var definitions = List.of(new Node("a","熟练","说明"),new Node("b","基本","说明"),new Node("c","听过","说明"),new Node("d","陌生","说明"));
    when(model.generateGraph(anyString())).thenReturn(new Graph(definitions,
        definitions.stream().map(n -> new Edge(n.key(),"target")).toList(), "目标的具体介绍"));
    // 故意返回超过请求数量的结果，后端也必须限制保存与展示数量。
    when(search.search(anyString(),anyInt())).thenReturn(java.util.stream.IntStream.range(0,7)
        .mapToObj(i -> new Resource("资料"+i,"https://www.zhihu.com/question/"+(i+1),null,null,null)).toList());
    long id=create();waitFor(id,"READY");
    verifyNoInteractions(search);
    String[] answers={"VERY_FAMILIAR","BASICALLY_KNOW","HEARD_OF","DONT_KNOW"};
    int[] counts={0,2,3,5};
    for(int i=0;i<4;i++) answer(id,questionId(id,definitions.get(i).name()),answers[i]);
    complete(id);
    var result=store.completeOwned(1,id);
    assertThat(result.nodes()).hasSize(5);
    assertThat(result.edges()).hasSize(4);
    assertThat(result.missingCount()).isEqualTo(3);
    for(int i=0;i<4;i++) {
      final int index=i;
      var node=result.nodes().stream().filter(n -> n.name().equals(definitions.get(index).name())).findFirst().orElseThrow();
      assertThat(node.answer()).isEqualTo(answers[i]);
      assertThat(node.resourceLimit()).isEqualTo(counts[i]);
      assertThat(store.findResourcesOwned(1,id,Long.parseLong(node.id())).resources()).hasSize(counts[i]);
    }
    verify(search,never()).search(eq("熟练"),anyInt());
    verify(search).search("基本",2);verify(search).search("听过",3);verify(search).search("陌生",5);
    clearInvocations(search);
    complete(id); // 刷新、重复提交不发起新搜索。
    answer(id,questionId(id,"基本"),"BASICALLY_KNOW");complete(id);
    verifyNoInteractions(search);
    answer(id,questionId(id,"基本"),"DONT_KNOW");complete(id);
    verify(search).search("基本",5);verifyNoMoreInteractions(search);
    answer(id,questionId(id,"基本"),"VERY_FAMILIAR");complete(id);
    assertThat(store.findResourcesOwned(1,id,nodeId(id,"基本")).resources()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM node_resources WHERE node_id=?",Integer.class,nodeId(id,"基本"))).isZero();
  }
  @Test void repeatedCompletionDoesNotDispatchTwiceAndFreezesAnswersWhileSearching() throws Exception {
    var entered=new java.util.concurrent.CountDownLatch(1);
    var release=new java.util.concurrent.CountDownLatch(1);
    when(search.search(anyString(),anyInt())).thenAnswer(invocation -> {
      entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return List.of();
    });
    long id=create();waitFor(id,"READY");answer(id,questionId(id),"DONT_KNOW");
    String path="/api/v1/learning-sessions/"+id;
    try {
      mvc.perform(post(path+"/complete").session(session).header("X-CSRF-Token",csrf))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SEARCHING_RESOURCES"));
      assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      mvc.perform(post(path+"/complete").session(session).header("X-CSRF-Token",csrf))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SEARCHING_RESOURCES"));
      mvc.perform(put(path+"/answers/"+questionId(id)).session(session).header("X-CSRF-Token",csrf)
          .contentType("application/json").content("{\"answer\":\"VERY_FAMILIAR\"}"))
          .andExpect(status().isConflict());
    } finally { release.countDown(); }
    waitFor(id,"COMPLETED");verify(search,times(1)).search("矩阵运算",5);
  }
  @Test void interruptedResourceSearchKeepsAnswersAndCanBeResubmitted() throws Exception {
    long id=create();waitFor(id,"READY");answer(id,questionId(id),"HEARD_OF");
    store.completeOwned(1,id); // 模拟已持久化状态但进程在执行前重启。
    store.recoverInterrupted();
    assertThat(store.findOwned(1,id).status()).isEqualTo("READY");
    assertThat(store.findQuestionsOwned(1,id).questions().getFirst().answer()).isEqualTo("HEARD_OF");
    complete(id);verify(search).search("矩阵运算",3);
  }
  private long create() throws Exception {
    var result=mvc.perform(post("/api/v1/learning-sessions").session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"target\":\"Transformer\",\"userId\":\"2\"}"))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("GENERATING_GRAPH")).andReturn();
    return Long.parseLong(json.readTree(result.getResponse().getContentAsString()).path("sessionId").asText());
  }
  private void seedUser(long id, String zhihuUserId) {
    Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?",Integer.class,id);
    if (count != null && count == 0) {
      jdbc.update("INSERT INTO users(id,zhihu_user_id,created_at) VALUES (?,?,?)",id,zhihuUserId,"now");
    }
  }
  private long questionId(long sessionId) {
    return jdbc.queryForObject("SELECT q.id FROM assessment_questions q JOIN knowledge_nodes n ON n.id=q.node_id WHERE n.session_id=?",Long.class,sessionId);
  }
  private long questionId(long sessionId, String nodeName) {
    return jdbc.queryForObject("SELECT q.id FROM assessment_questions q JOIN knowledge_nodes n ON n.id=q.node_id WHERE n.session_id=? AND n.name=?",Long.class,sessionId,nodeName);
  }
  private long nodeId(long sessionId, String nodeName) {
    return jdbc.queryForObject("SELECT id FROM knowledge_nodes WHERE session_id=? AND name=?",Long.class,sessionId,nodeName);
  }
  private void answer(long sessionId, long questionId, String value) throws Exception {
    mvc.perform(put("/api/v1/learning-sessions/"+sessionId+"/answers/"+questionId).session(session).header("X-CSRF-Token",csrf)
        .contentType("application/json").content("{\"answer\":\""+value+"\"}"))
        .andExpect(status().isOk());
  }
  private void complete(long sessionId) throws Exception {
    mvc.perform(post("/api/v1/learning-sessions/"+sessionId+"/complete").session(session).header("X-CSRF-Token",csrf))
        .andExpect(status().isOk());
    waitFor(sessionId,"COMPLETED");
  }
  private void waitFor(long id,String status) { await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(store.findOwned(1,id).status()).isEqualTo(status)); }
}
