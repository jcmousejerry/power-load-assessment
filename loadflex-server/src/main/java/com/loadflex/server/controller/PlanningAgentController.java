package com.loadflex.server.controller;

import com.loadflex.server.api.ApiResponse;
import com.loadflex.server.security.UserContext;
import com.loadflex.server.service.PlanningAgentService;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/grid-planning-agent")
public class PlanningAgentController {
    private final PlanningAgentService agentService;

    public PlanningAgentController(PlanningAgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> sessions() {
        return ApiResponse.ok(agentService.listSessions());
    }

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Object>> createSession(@RequestBody(required = false) CreateSessionRequest request) {
        return ApiResponse.ok("Agent会话已创建", agentService.createSession(request == null ? null : request.title()));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> session(@PathVariable long sessionId) {
        return ApiResponse.ok(agentService.sessionDetail(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable long sessionId) {
        agentService.deleteSession(sessionId);
        return ApiResponse.ok("AI分析记录已删除", null);
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> message(
            @PathVariable long sessionId, @Valid @RequestBody MessageRequest request) {
        return ApiResponse.ok("Agent已开始编制方案", agentService.sendMessage(sessionId, request.content()));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<Map<String, Object>> run(@PathVariable long runId) {
        return ApiResponse.ok(agentService.run(runId));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long runId) {
        SseEmitter emitter = new SseEmitter(130_000L);
        agentService.stream(runId, UserContext.get().getUserId(), emitter);
        return emitter;
    }

    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable long runId) {
        return ApiResponse.ok(agentService.cancel(runId));
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable long approvalId) {
        return ApiResponse.ok("已批准并执行Agent写操作", agentService.decideApproval(approvalId, true));
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable long approvalId) {
        return ApiResponse.ok("已拒绝Agent写操作", agentService.decideApproval(approvalId, false));
    }

    public record CreateSessionRequest(String title) {}

    public record MessageRequest(@NotBlank String content) {}
}
