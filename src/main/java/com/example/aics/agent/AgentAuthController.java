package com.example.aics.agent;

import com.example.aics.audit.AuditService;
import com.example.aics.chat.ConversationService;
import com.example.aics.chat.SessionRegistry;
import com.example.aics.chat.UserProfileService;
import com.example.aics.config.ConfigService;
import com.example.aics.config.LoginRateLimiter;
import com.example.aics.config.WebAuthCookieService;
import com.example.aics.image.ImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import java.util.List;
import java.util.Map;

/**
 * 坐席工作台接口（用坐席 token 鉴权，和管理口令是两套体系）
 *
 * 坐席令牌默认由 HttpOnly Cookie 承载，请求头只用于兼容旧客户端和自动化测试；
 * WebSocket 握手同样读取 Cookie，不再把令牌放入 URL。
 *
 * POST /agent/login      登录（失败次数由 Redis 按账号+IP 限制）
 * POST /agent/logout     登出
 * GET  /agent/queue      排队队列
 * POST /agent/take       从队列接入（{"conversationId":"..."}）
 * GET  /agent/mine       我的会话
 * GET  /agent/profile    客户画像（?userId=）
 * POST /agent/close      结束会话（{"conversationId":"..."}）
 * GET  /agent/quick-replies  坐席快捷回复（后台可配置）
 * POST /agent/images     坐席发图上传
 * GET  /agent/online-agents  在线坐席列表（转接选择）
 * POST /agent/transfer   会话转接（P2-12）
 */
@RestController
@RequestMapping("/agent")
public class AgentAuthController {

    /** 与 agent.html 原内置话术一致的兜底默认（后台 agent.quickReplies 未配置时使用）。 */
    private static final String DEFAULT_QUICK_REPLIES = String.join("\n",
            "您好，我是昇媛的客服小助手，请问有什么可以帮您？",
            "我们门店地址：云南省昆明市五华区XX路XX号，欢迎到店咨询～",
            "目前抗衰项目有优惠活动，具体价格我帮您查询一下，您方便留个电话吗？",
            "您可以先在聊天里登记预约，我们会安排专人联系您～",
            "本次服务结束，感谢您的咨询，再见～");

    private final AgentAccountService agentAccountService;
    private final ConversationService conversationService;
    private final UserProfileService userProfileService;
    private final AuditService auditService;
    private final LoginRateLimiter loginRateLimiter;
    private final WebAuthCookieService authCookies;
    private final HumanReplyService humanReplies;
    private final ConfigService configService;
    private final ImageService imageService;
    private final SessionRegistry sessionRegistry;
    private static final ObjectMapper JSON = new ObjectMapper();

    public AgentAuthController(AgentAccountService agentAccountService,
                               ConversationService conversationService,
                               UserProfileService userProfileService,
                               AuditService auditService,
                               LoginRateLimiter loginRateLimiter,
                               WebAuthCookieService authCookies,
                               HumanReplyService humanReplies,
                               ConfigService configService,
                               ImageService imageService,
                               SessionRegistry sessionRegistry) {
        this.agentAccountService = agentAccountService;
        this.conversationService = conversationService;
        this.userProfileService = userProfileService;
        this.auditService = auditService;
        this.loginRateLimiter = loginRateLimiter;
        this.authCookies = authCookies;
        this.humanReplies = humanReplies;
        this.configService = configService;
        this.imageService = imageService;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 坐席快捷回复（后台可配置）：值来自 AI 策略配置页的 agent.quickReplies（每行一条），
     * 未配置时返回与前端一致的内置默认，保证开箱可用。
     */
    @GetMapping("/quick-replies")
    public Object quickReplies(HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        String raw = configService.get("agent.quickReplies", DEFAULT_QUICK_REPLIES);
        List<String> items = Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(30)
                .toList();
        return Map.of("ok", true, "items", items);
    }

    /** 坐席发送图片：上传到聊天图片目录（a_ 前缀），返回可发送的 url。 */
    @PostMapping("/images")
    public Object uploadImage(@RequestParam("file") MultipartFile file, HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        try {
            Map<String, Object> saved = imageService.saveAgentImage(file);
            auditService.log("AGENT_SEND_IMAGE", "坐席发送图片 " + saved.get("name"),
                    String.valueOf(agent.get("username")));
            return Map.of("ok", true, "url", saved.get("url"), "name", saved.get("name"));
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        } catch (Exception e) {
            return Map.of("ok", false, "error", "图片保存失败，请稍后重试");
        }
    }

    /** 在线坐席列表（转接选择用，不含自己）。 */
    @GetMapping("/online-agents")
    public Object onlineAgents(HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        long agentId = ((Number) agent.get("id")).longValue();
        return Map.of("ok", true, "items", agentAccountService.onlineAgents(agentId));
    }

    /**
     * 会话转接（P2-12）：把当前服务中的会话移交给另一位在线坐席。
     * 双向 WS 通知 + 客户告知；目标坐席推送失败时回退到排队队列，避免会话悬空。
     */
    @PostMapping("/transfer")
    public Object transfer(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        long fromAgentId = ((Number) agent.get("id")).longValue();
        String conversationId = body.get("conversationId") == null ? "" : String.valueOf(body.get("conversationId")).trim();
        Object targetRaw = body.get("targetAgentId");
        if (conversationId.isBlank() || targetRaw == null) {
            return Map.of("ok", false, "error", "参数不完整");
        }
        long targetAgentId;
        try {
            targetAgentId = Long.parseLong(String.valueOf(targetRaw));
        } catch (NumberFormatException e) {
            return Map.of("ok", false, "error", "目标坐席无效");
        }

        String fromName = String.valueOf(agent.get("name"));
        String targetName = agentAccountService.transfer(conversationId, fromAgentId, targetAgentId);
        if (targetName == null) {
            return Map.of("ok", false, "error", "转接失败：会话状态已变化或目标坐席不可用");
        }
        boolean notified = notifyAgent(targetAgentId, Map.of(
                "type", "transfer_in", "conversationId", conversationId, "fromName", fromName));
        if (!notified) {
            agentAccountService.releaseToQueue(conversationId);
            return Map.of("ok", false, "error", "目标坐席连接已断开，会话已退回排队队列");
        }
        notifyAgent(fromAgentId, Map.of(
                "type", "transfer_out", "conversationId", conversationId, "toName", targetName));
        try {
            sessionRegistry.sendToCustomer(conversationId, JSON.writeValueAsString(Map.of(
                    "type", "notice", "content", "已为您转接人工客服「" + targetName + "」，请继续～")));
        } catch (Exception ignored) { /* 客户端告知失败不影响转接 */ }
        auditService.log("AGENT_TRANSFER",
                "会话 " + conversationId + " 由 " + fromName + " 转接给 " + targetName,
                String.valueOf(agent.get("username")));
        return Map.of("ok", true, "targetName", targetName);
    }

    private boolean notifyAgent(long agentId, Map<String, Object> payload) {
        try {
            return sessionRegistry.sendToAgent(agentId, JSON.writeValueAsString(payload));
        } catch (Exception e) {
            return false;
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        String username = body.get("username");
        String ip = request.getRemoteAddr();
        if (loginRateLimiter.isBlocked(username, ip)) {
            return tooManyAttempts();
        }
        Map<String, Object> result = agentAccountService.login(username, body.get("password"));
        if (Boolean.TRUE.equals(result.get("ok"))) {
            String token = String.valueOf(result.get("token"));
            String role = String.valueOf(result.get("role"));
            // 坐席工作台封存（W26 决策）：人工接待已迁移至企业微信原生接待，
            // 网页渠道仅保留 AI 自助答疑。管理员/超级管理员登录不受影响。
            if (AgentAccountService.ROLE_AGENT.equals(role)
                    && !configService.getBoolean("agent.workbenchEnabled", false)) {
                agentAccountService.logout(token);
                auditService.log("AGENT_LOGIN_BLOCKED", "坐席工作台已封存（人工接待迁移至企微原生接待）",
                        username);
                response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                        authCookies.expiredCookie(WebAuthCookieService.AGENT_COOKIE,
                                authCookies.isSecure(request)).toString());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "ok", false,
                        "error", "坐席工作台已暂停使用：人工接待已迁移至企业微信原生接待，网页渠道仅保留智能客服答疑。"));
            }
            String portal = body.get("portal");
            boolean wrongAgentPortal = "agent".equals(portal)
                    && !AgentAccountService.ROLE_AGENT.equals(role);
            boolean wrongAdminPortal = "admin".equals(portal)
                    && !AgentAccountService.ROLE_ADMIN.equals(role)
                    && !AgentAccountService.ROLE_SUPER_ADMIN.equals(role);
            if (wrongAgentPortal || wrongAdminPortal) {
                agentAccountService.logout(token);
                String staleCookie = wrongAgentPortal
                        ? WebAuthCookieService.AGENT_COOKIE : WebAuthCookieService.ADMIN_COOKIE;
                response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                        authCookies.expiredCookie(staleCookie, authCookies.isSecure(request)).toString());
                String error = wrongAgentPortal
                        ? "该账号不是坐席账号，请使用管理员分配的坐席账号登录"
                        : "该账号没有管理后台权限";
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("ok", false, "error", error));
            }
            loginRateLimiter.recordSuccess(username, ip);
            String cookieName = AgentAccountService.ROLE_AGENT.equals(role)
                    ? WebAuthCookieService.AGENT_COOKIE : WebAuthCookieService.ADMIN_COOKIE;
            response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                    authCookies.sessionCookie(cookieName, token, authCookies.isSecure(request)).toString());
            Map<String, Object> safeResult = new java.util.LinkedHashMap<>(result);
            safeResult.remove("token");
            return ResponseEntity.ok(safeResult);
        }
        if (loginRateLimiter.recordFailure(username, ip)) {
            return tooManyAttempts();
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    /** 坐席页恢复登录状态时以服务端 Cookie 为准，不信任浏览器中的显示标记。 */
    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> session(HttpServletRequest request) {
        Map<String, Object> current = agent(request);
        if (current == null) return unauthorizedResponse();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "name", String.valueOf(current.get("name")),
                "username", String.valueOf(current.get("username")),
                "role", AgentAccountService.ROLE_AGENT));
    }

    private ResponseEntity<Map<String, Object>> tooManyAttempts() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "900")
                .body(Map.of("ok", false, "error", "登录失败次数过多，请 15 分钟后再试"));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse response) {
        String currentToken = authCookies.agentToken(req);
        if (currentToken == null) currentToken = authCookies.adminToken(req);
        agentAccountService.logout(currentToken);
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                authCookies.expiredCookie(WebAuthCookieService.AGENT_COOKIE, authCookies.isSecure(req)).toString());
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                authCookies.expiredCookie(WebAuthCookieService.ADMIN_COOKIE, authCookies.isSecure(req)).toString());
        return Map.of("ok", true);
    }

    @GetMapping("/queue")
    public Object queue(HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        return agentAccountService.queue(((Number) agent.get("id")).longValue());
    }

    @PostMapping("/take")
    public Object take(@RequestBody Map<String, String> body, HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        long agentId = ((Number) agent.get("id")).longValue();
        boolean ok = agentAccountService.take(body.get("conversationId"), agentId);
        return Map.of("ok", ok, "agentName", agentAccountService.getAgentName(agentId));
    }

    @GetMapping("/mine")
    public Object mine(HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        return agentAccountService.myConversations(((Number) agent.get("id")).longValue());
    }

    /**
     * W26-A1：客户画像面板数据。只有坐席当前正在服务且 userId 匹配的会话才允许查询，
     * 历史会话与预约也只统计该会话所属企业/客服号范围，不能按任意 userId 横向读取。
     * W28-安全加固：每次查询记录审计日志（谁在何时查了哪个客户），
     * 便于内部滥用时追溯；同时对 userId 做基本参数校验。
     */
    @GetMapping("/profile")
    public Object profile(@RequestParam String userId,
                          @RequestParam String conversationId,
                          HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        if (userId == null || userId.isBlank() || userId.length() > 128
                || conversationId == null || conversationId.isBlank() || conversationId.length() > 128) {
            return Map.of("ok", false, "error", "客户或会话参数不合法");
        }
        long agentId = ((Number) agent.get("id")).longValue();
        Map<String, Object> stats = agentAccountService.customerStats(agentId, conversationId, userId);
        if (stats == null) return Map.of("ok", false, "error", "该客户不属于您当前服务的会话");
        // 审计：坐席账号查询客户画像的记录，供后台 audit 追溯内部访问
        try {
            auditService.log("AGENT_VIEW_PROFILE", "查询客户画像 conversationId=" + conversationId,
                    String.valueOf(agent.get("username")));
        } catch (RuntimeException ignored) { /* 审计失败不阻断查询 */ }
        String profile = userProfileService.getProfileText(userId);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("userId", userId);
        result.put("profile", profile);
        result.putAll(stats);
        return result;
    }

    /**
     * 坐席读取会话消息（P0-2 连带）：/api/messages 已改为客户归属校验，
     * 坐席看不到客户 userId，故走本端点。
     * 权限：已接入的坐席可读；排队中（need_human）的会话任何坐席可预览
     * （只读，用于接入前了解上下文；接入后才能回复，回复权仍由 isServing 独占）。
     */
    @GetMapping("/messages")
    public Object messages(@RequestParam String conversationId, HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        long agentId = ((Number) agent.get("id")).longValue();
        if (!agentAccountService.isServing(agentId, conversationId)
                && !agentAccountService.isPreviewable(agentId, conversationId)) {
            return Map.of("ok", false, "error", "该会话不属于您");
        }
        List<Map<String, Object>> rows = conversationService.listMessages(conversationId);
        // 客户展示名回填（与 queue/mine 列表同一口径）：给每条消息补 userName，
        // 前端聊天区头像与顶栏据此显示客户名（网页匿名访客无名字则前端回退「访客」）。
        if (!rows.isEmpty()) {
            String userName = agentAccountService.resolveCustomerName(conversationId);
            if (userName != null && !userName.isBlank()) {
                String name = userName;
                rows.forEach(row -> row.put("userName", name));
            }
        }
        return rows;
    }

    @PostMapping("/close")
    public Object close(@RequestBody Map<String, String> body, HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        long agentId = ((Number) agent.get("id")).longValue();
        String conversationId = body.get("conversationId");
        if (!agentAccountService.isServing(agentId, conversationId)) {
            return Map.of("ok", false, "error", "该会话已结束或不属于您");
        }
        String farewell = body.get("farewell");
        boolean delivered = true;
        if (farewell != null && !farewell.isBlank()) {
            String content = farewell.trim();
            if (content.length() > 4000) {
                return Map.of("ok", false, "error", "结束语不能超过 4000 个字符");
            }
            HumanReplyService.Delivery delivery = humanReplies.send(agentId, conversationId, content);
            if (!delivery.accepted()) {
                return Map.of("ok", false, "error", delivery.error());
            }
            delivered = delivery.liveDelivered();
        }
        boolean closed = agentAccountService.close(conversationId, agentId);
        if (!closed) return Map.of("ok", false, "error", "会话状态已变化，请刷新后重试");
        return Map.of("ok", true, "farewellDelivered", delivered);
    }

    /** 坐席改密（校验旧密码） */
    @PostMapping("/change-password")
    public Object changePassword(@RequestBody Map<String, String> body, HttpServletRequest req) {
        Map<String, Object> agent = agent(req);
        if (agent == null) return unauthorizedResponse();
        String newPassword = body.get("newPassword");
        String confirmation = body.get("confirmPassword");
        if (newPassword == null || confirmation == null || !newPassword.equals(confirmation)) {
            return Map.of("ok", false, "error", "两次输入的新密码不一致");
        }
        boolean ok = agentAccountService.changePassword(
                ((Number) agent.get("id")).longValue(),
                body.get("oldPassword"), newPassword);
        if (ok) {
            auditService.log("CHANGE_PASSWORD", "账号=" + agent.get("username"), String.valueOf(agent.get("name")));
        }
        return Map.of("ok", ok, "error", ok ? "" : "旧密码不对");
    }

    /** 从 HttpOnly Cookie 读取坐席 token，请求头仅兼容旧客户端；绝不从 URL/body 读取。 */
    private String token(HttpServletRequest req) {
        return authCookies.agentToken(req);
    }

    private Map<String, Object> agent(HttpServletRequest req) {
        Map<String, Object> session = agentAccountService.validate(token(req));
        return session != null && AgentAccountService.ROLE_AGENT.equals(String.valueOf(session.get("role")))
                ? session : null;
    }

    private ResponseEntity<Map<String, Object>> unauthorizedResponse() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("ok", false, "error", "坐席登录已失效，请重新登录"));
    }

}
