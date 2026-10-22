package com.example.aics.agent;

import com.example.aics.chat.SessionRegistry;
import com.example.aics.config.AdminDataScope;
import com.example.aics.state.StateStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.aics.config.AdminPermissionCatalog;

/**
 * 坐席账号服务：登录/登出/在线状态/按授权企业与客服号隔离的轮询分配。
 *
 * 轮询规则：网页会话进入全局池；企微会话只在服务范围包含该企业/客服号的坐席中，
 * 按"最久没接单"顺序分配（last_assigned_time 最小者优先）。
 *
 * 第 23 周安全加固：口令全部 BCrypt 哈希存储；启动时把历史明文自动升级为哈希。
 * 幽灵坐席修复（2026-08-07）：轮询分配前必须校验坐席 WS 真的在线（SessionRegistry），
 * 且应用启动时把所有坐席强制置 offline——防止 token 失效后数据库 status 残留 online，
 * 导致客户被"接入"一个实际收不到消息的坐席（消息静默丢失）。
 * 登录 token 存 StateStore（Redis，24 小时 TTL）：应用重启不丢登录（ADR-003）。
 *
 * 不再创建或接受固定默认口令。新库与历史 agent123 账号必须通过一次性环境变量
 * AICS_BOOTSTRAP_ADMIN_PASSWORD 完成初始化。
 */
@Service
public class AgentAccountService {

    private static final Logger log = LoggerFactory.getLogger(AgentAccountService.class);
    public static final String ROLE_SUPER_ADMIN = "super_admin";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_AGENT = "agent";

    /**
     * 客户展示名解析（SQL 片段，引用会话别名 c）：
     * 企微客户昵称（external_user.nickname）→ 最近一次预约登记姓名（appointment.name）→ NULL（网页匿名访客）。
     * queue / mine / messages 三处共用同一口径，保证列表与聊天区看到同一个名字。
     */
    private static final String CUSTOMER_NAME_SQL =
            "COALESCE(" +
            "  (SELECT eu.nickname FROM external_user eu WHERE eu.user_id = c.user_id" +
            "    AND eu.nickname IS NOT NULL AND eu.nickname <> ''" +
            "    ORDER BY eu.create_time DESC LIMIT 1)," +
            "  (SELECT a.name FROM appointment a WHERE a.user_id = c.user_id" +
            "    AND a.name IS NOT NULL AND a.name <> ''" +
            "    ORDER BY a.create_time DESC, a.id DESC LIMIT 1)," +
            "  NULL) AS user_name";

    private final JdbcTemplate jdbc;
    private final SessionRegistry sessionRegistry;
    private final StateStore stateStore;
    private final String bootstrapAdminPassword;

    /** BCrypt 口令编码器（默认强度 10，单例常驻） */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AgentAccountService(JdbcTemplate jdbc, SessionRegistry sessionRegistry,
                               StateStore stateStore,
                               @Value("${aics.bootstrap-admin-password:}") String bootstrapAdminPassword) {
        this.jdbc = jdbc;
        this.sessionRegistry = sessionRegistry;
        this.stateStore = stateStore;
        this.bootstrapAdminPassword = bootstrapAdminPassword == null ? "" : bootstrapAdminPassword;
    }

    /**
     * 启动迁移：把 agent 表里仍为明文的密码升级为 BCrypt 哈希。
     * BCrypt 哈希一律以 "$2" 开头，据此识别；升级后 matches(原明文) 自然通过，无需用户改密码。
     * 同时把全部坐席状态重置为 offline——应用重启后 WS 连接全断（SessionRegistry 在内存），
     * 数据库里残留的 online 是"幽灵状态"，必须清除（否则会被轮询分配到却收不到消息）。
     * 登录 token 已持久化（Redis）不失效，坐席重新连上工作台后即恢复在线状态。
     */
    @PostConstruct
    public void migratePlaintextPasswords() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, password FROM agent");
        for (Map<String, Object> row : rows) {
            Object pwd = row.get("password");
            if (pwd == null) continue;
            String stored = String.valueOf(pwd);
            if (!stored.startsWith("$2")) {
                long agentId = ((Number) row.get("id")).longValue();
                jdbc.update("UPDATE agent SET password = ? WHERE id = ?",
                        encoder.encode(stored), agentId);
            }
        }
        ensureSecureAdminAccount();
        jdbc.update("UPDATE agent SET status = 'offline' WHERE status <> 'offline'");
    }

    /**
     * 新库不再插入固定账号。首次启动用一次性环境变量创建管理员；历史 agent123
     * 账号也必须用该变量替换，否则拒绝启动，任何 profile 都不存在默认口令窗口。
     */
    private void ensureSecureAdminAccount() {
        List<Map<String, Object>> admins = jdbc.queryForList(
                "SELECT id, password FROM agent WHERE role = 'super_admin' ORDER BY id");
        if (admins.isEmpty()) {
            if (bootstrapAdminPassword.isBlank()) {
                throw new IllegalStateException(
                        "数据库中没有管理员账号。请设置一次性环境变量 AICS_BOOTSTRAP_ADMIN_PASSWORD " +
                        "（至少 8 位）后启动");
            }
            validatePassword(bootstrapAdminPassword);
            jdbc.update("INSERT INTO agent(username, name, password, role) " +
                            "VALUES ('admin', '系统管理员', ?, 'super_admin')",
                    encoder.encode(bootstrapAdminPassword));
            log.info("已使用 AICS_BOOTSTRAP_ADMIN_PASSWORD 创建初始管理员；请从运行环境移除该变量");
            return;
        }
        String stored = String.valueOf(admins.get(0).get("password"));
        if (encoder.matches("agent123", stored)) {
            if (bootstrapAdminPassword.isBlank()) {
                throw new IllegalStateException(
                        "检测到历史默认管理员密码 agent123。请设置一次性环境变量 " +
                        "AICS_BOOTSTRAP_ADMIN_PASSWORD（至少 8 位）后重启");
            }
            validatePassword(bootstrapAdminPassword);
            jdbc.update("UPDATE agent SET password = ?, role = 'super_admin' WHERE username = 'admin'",
                    encoder.encode(bootstrapAdminPassword));
            log.info("已替换历史默认管理员密码；请从运行环境移除 AICS_BOOTSTRAP_ADMIN_PASSWORD");
        }
    }

    /** 登录：校验账号密码（BCrypt 比对）→ 上线 → 发 token */
    public Map<String, Object> login(String username, String password) {
        if (username == null || username.isBlank()) {
            return Map.of("ok", false, "error", "账号或密码不对");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, password, role FROM agent WHERE username = ? AND enabled = TRUE",
                username);
        if (rows.isEmpty()) {
            return Map.of("ok", false, "error", "账号或密码不对");
        }
        Object storedObj = rows.get(0).get("password");
        String stored = storedObj == null ? "" : String.valueOf(storedObj);
        if (password == null || !encoder.matches(password, stored)) {
            return Map.of("ok", false, "error", "账号或密码不对");
        }
        long agentId = ((Number) rows.get(0).get("id")).longValue();
        String name = String.valueOf(rows.get(0).get("name"));
        String role = String.valueOf(rows.get(0).get("role"));
        jdbc.update("UPDATE agent SET status = 'online' WHERE id = ?", agentId);
        // 单点登录（替换原 P1-6 多会话共存）：同一账号新登录使所有旧会话失效——
        // 其他电脑/其他浏览器的旧 token 全部作废、旧 WS 连接断开，旧端会被引导重新登录。
        // 安全敏感操作（改密/停号/改权限）的全量失效逻辑不变（见 changePassword 等）。
        notifyKicked(agentId);                       // 先发"被踢"通知（WS 关闭前发送，确保送达旧端）
        stateStore.removeAllAuth(agentId);           // 作废旧 token
        sessionRegistry.closeAllAgentSessions(agentId);   // 断开旧 WS 连接
        String token = UUID.randomUUID().toString();
        stateStore.putAuth(token, agentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("token", token);
        result.put("agentId", agentId);
        result.put("name", name);
        result.put("username", username);
        result.put("role", role);
        result.put("permissions", permissionsFor(agentId, role));
        return result;
    }

    private static final ObjectMapper KICK_JSON = new ObjectMapper();

    /** 单点登录踢人通知：在关闭旧 WS 连接之前发送，确保旧设备/旧页面能收到并自行下线。 */
    private void notifyKicked(long agentId) {
        try {
            sessionRegistry.sendToAgent(agentId, KICK_JSON.writeValueAsString(Map.of(
                    "type", "kicked",
                    "content", "该账号已在其他地方登录")));
        } catch (Exception ignored) { /* 通知失败不影响登录主流程 */ }
    }

    /** 登出：下线 + 注销 token */
    public void logout(String token) {
        Long agentId = stateStore.getAuth(token);
        if (agentId != null) {
            stateStore.removeAuth(token);
            sessionRegistry.closeAgentSessionsForToken(agentId, token);
            // 同一坐席允许多标签页/多终端；注销一个 token 不能把仍有 WS 连接的坐席误标离线。
            if (!sessionRegistry.isAgentOnline(agentId)) {
                jdbc.update("UPDATE agent SET status = 'offline' WHERE id = ?", agentId);
            }
        }
    }

    /** 坐席下线（WebSocket 断开且无其他连接时调用，P1-7） */
    public void markOffline(long agentId) {
        jdbc.update("UPDATE agent SET status = 'offline' WHERE id = ?", agentId);
    }

    /** Redis 登录态可跨应用重启保留；工作台 WebSocket 重连时必须恢复数据库在线状态。 */
    public void markOnline(long agentId) {
        jdbc.update("UPDATE agent SET status = 'online' WHERE id = ? AND enabled = TRUE AND role = 'agent'",
                agentId);
    }

    /** 校验 token，返回坐席信息含角色（无效返回 null） */
    public Map<String, Object> validate(String token) {
        Long agentId = stateStore.getAuth(token);
        if (agentId == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, username, role FROM agent WHERE id = ? AND enabled = TRUE", agentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该 token 是否属于任一后台管理角色。 */
    public boolean isAdminToken(String token) {
        Map<String, Object> agent = validate(token);
        if (agent == null) return false;
        String role = String.valueOf(agent.get("role"));
        return ROLE_ADMIN.equals(role) || ROLE_SUPER_ADMIN.equals(role);
    }

    public boolean isSuperAdminToken(String token) {
        Map<String, Object> agent = validate(token);
        return agent != null && ROLE_SUPER_ADMIN.equals(String.valueOf(agent.get("role")));
    }

    /** 敏感账号操作二次认证：当前登录 token 与当前密码必须属于同一个账号。 */
    public boolean verifyCurrentPassword(String token, String password) {
        Long agentId = stateStore.getAuth(token);
        if (agentId == null || password == null || password.isBlank()) return false;
        List<String> rows = jdbc.queryForList(
                "SELECT password FROM agent WHERE id = ? AND enabled = TRUE", String.class, agentId);
        return !rows.isEmpty() && rows.get(0) != null && encoder.matches(password, rows.get(0));
    }

    /** 超级管理员拥有全部权限；普通管理员必须有明确分配记录。 */
    public boolean hasAdminPermission(String token, String permissionCode) {
        Map<String, Object> agent = validate(token);
        if (agent == null) return false;
        String role = String.valueOf(agent.get("role"));
        if (ROLE_SUPER_ADMIN.equals(role)) return true;
        if (!ROLE_ADMIN.equals(role) || !AdminPermissionCatalog.isKnown(permissionCode)) return false;
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM admin_permission WHERE agent_id = ? AND permission_code = ?",
                Integer.class, ((Number) agent.get("id")).longValue(), permissionCode);
        return count != null && count > 0;
    }

    /** 当前后台登录身份，供前端恢复角色和菜单。 */
    public Map<String, Object> adminSession(String token) {
        Map<String, Object> agent = validate(token);
        if (agent == null) return null;
        String role = String.valueOf(agent.get("role"));
        if (!ROLE_ADMIN.equals(role) && !ROLE_SUPER_ADMIN.equals(role)) return null;
        long id = ((Number) agent.get("id")).longValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("username", agent.get("username"));
        result.put("name", agent.get("name"));
        result.put("role", role);
        result.put("superAdmin", ROLE_SUPER_ADMIN.equals(role));
        result.put("permissions", permissionsFor(id, role));
        result.putAll(dataScopeFor(id, role));
        return result;
    }

    public List<String> permissionsFor(long agentId, String role) {
        if (ROLE_SUPER_ADMIN.equals(role)) return AdminPermissionCatalog.codes();
        if (!ROLE_ADMIN.equals(role)) return List.of();
        Set<String> assigned = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT permission_code FROM admin_permission WHERE agent_id = ?",
                String.class, agentId));
        return AdminPermissionCatalog.codes().stream().filter(assigned::contains).toList();
    }

    /** 坐席改密（校验旧密码，BCrypt 比对） */
    public boolean changePassword(long agentId, String oldPassword, String newPassword) {
        validatePassword(newPassword);
        List<String> rows = jdbc.queryForList(
                "SELECT password FROM agent WHERE id = ?", String.class, agentId);
        if (rows.isEmpty() || rows.get(0) == null) return false;
        String stored = rows.get(0);
        if (oldPassword == null || !encoder.matches(oldPassword, stored)) {
            return false;
        }
        boolean changed = jdbc.update(
                "UPDATE agent SET password = ? WHERE id = ?",
                encoder.encode(newPassword == null ? "" : newPassword), agentId) > 0;
        if (changed) {
            stateStore.removeAllAuth(agentId);
            sessionRegistry.closeAllAgentSessions(agentId);
            jdbc.update("UPDATE agent SET status = 'offline' WHERE id = ?", agentId);
        }
        return changed;
    }

    /** 坐席列表（管理员用，不返回密码） */
    public List<Map<String, Object>> listAgents() {
        List<Object> args = new ArrayList<>();
        String filter = agentManagementFilter("a", args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.id, a.username, a.name, a.role, a.status, a.enabled, a.last_assigned_time " +
                        "FROM agent a WHERE a.role = 'agent'" + filter + " ORDER BY a.id",
                args.toArray());
        return enrichAgentScopes(rows);
    }

    /** 坐席账号服务端分页，避免账号增长后一次加载全部记录。 */
    public Map<String, Object> listAgents(int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(100, Math.max(1, pageSize));
        List<Object> scopeArgs = new ArrayList<>();
        String filter = agentManagementFilter("a", scopeArgs);
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM agent a WHERE a.role = 'agent'" + filter,
                Integer.class, scopeArgs.toArray());
        List<Object> pageArgs = new ArrayList<>(scopeArgs);
        pageArgs.add(size);
        pageArgs.add((p - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.id, a.username, a.name, a.role, a.status, a.enabled, a.last_assigned_time " +
                        "FROM agent a WHERE a.role = 'agent'" + filter +
                        " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return Map.of("total", total == null ? 0 : total, "rows", enrichAgentScopes(rows));
    }

    /** 新增坐席（口令 BCrypt 哈希存储） */
    public void createAgent(String username, String name, String password) {
        validateUsername(username);
        validatePassword(password);
        String normalizedUsername = username.trim();
        String existingRole = findRoleByUsername(normalizedUsername);
        if (existingRole != null) {
            throw new AccountAlreadyExistsException(existingRole);
        }
        String displayName = normalizeDisplayName(name, normalizedUsername);
        try {
            jdbc.update("INSERT INTO agent(username, name, password, role) VALUES (?,?,?,?)",
                    normalizedUsername, displayName, encoder.encode(password), ROLE_AGENT);
        } catch (DuplicateKeyException e) {
            // 预检查与 INSERT 之间仍可能有并发创建，数据库唯一键是最终防线。
            throw new AccountAlreadyExistsException(findRoleByUsername(normalizedUsername), e);
        }
    }

    /** 新增坐席并设置企业/客服号服务范围；空企业范围表示只服务网页全局会话。 */
    @Transactional
    public long createAgent(String username, String name, String password,
                            List<Long> tenantIds, List<Long> kfAccountIds) {
        validateUsername(username);
        validatePassword(password);
        String normalizedUsername = username.trim();
        String existingRole = findRoleByUsername(normalizedUsername);
        if (existingRole != null) throw new AccountAlreadyExistsException(existingRole);
        List<Long> normalizedTenants = normalizeIds(tenantIds);
        List<Long> normalizedAccounts = normalizeIds(kfAccountIds);
        validateAgentDataScope(normalizedTenants, normalizedAccounts);
        String displayName = normalizeDisplayName(name, normalizedUsername);
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO agent(username, name, password, role) VALUES (?,?,?,'agent') RETURNING id",
                    Long.class, normalizedUsername, displayName, encoder.encode(password));
            if (id == null) throw new IllegalStateException("客服账号创建失败");
            replaceAgentDataScope(id, normalizedTenants, normalizedAccounts);
            return id;
        } catch (DuplicateKeyException e) {
            throw new AccountAlreadyExistsException(findRoleByUsername(normalizedUsername), e);
        }
    }

    private String findRoleByUsername(String username) {
        List<String> roles = jdbc.queryForList(
                "SELECT role FROM agent WHERE username = ?", String.class, username);
        return roles.isEmpty() ? null : roles.get(0);
    }

    /** 登录账号在客服、管理员、超级管理员之间全局唯一。 */
    public static final class AccountAlreadyExistsException extends IllegalArgumentException {
        public AccountAlreadyExistsException() {
            this(null, null);
        }

        public AccountAlreadyExistsException(String role) {
            this(role, null);
        }

        public AccountAlreadyExistsException(String role, Throwable cause) {
            super(messageFor(role), cause);
        }

        private static String messageFor(String role) {
            return switch (role == null ? "" : role) {
                case ROLE_SUPER_ADMIN -> "该登录账号已被超级管理员使用，不会显示在客服账号列表中，请更换一个账号";
                case ROLE_ADMIN -> "该登录账号已被管理员使用，不会显示在客服账号列表中，请更换一个账号";
                case ROLE_AGENT -> "该登录账号已被客服账号使用，请在下方客服账号列表中查看或更换账号";
                default -> "登录账号已存在，请更换一个账号";
            };
        }
    }

    /** 管理员重置坐席密码（BCrypt 哈希存储） */
    public void resetPassword(long agentId, String newPassword) {
        validatePassword(newPassword);
        requireManageableAgent(agentId);
        int updated = jdbc.update("UPDATE agent SET password = ? WHERE id = ? AND role = 'agent'",
                encoder.encode(newPassword == null ? "" : newPassword), agentId);
        if (updated == 0) throw new IllegalArgumentException("客服账号不存在");
        stateStore.removeAllAuth(agentId);
        sessionRegistry.closeAllAgentSessions(agentId);
        jdbc.update("UPDATE agent SET status = 'offline' WHERE id = ?", agentId);
    }

    /** 修改坐席服务范围；范围收窄后把越权的在服会话安全释放回排队队列。 */
    @Transactional
    public void setAgentDataScope(long agentId, List<Long> tenantIds, List<Long> kfAccountIds) {
        requireManageableAgent(agentId);
        List<Long> normalizedTenants = normalizeIds(tenantIds);
        List<Long> normalizedAccounts = normalizeIds(kfAccountIds);
        validateAgentDataScope(normalizedTenants, normalizedAccounts);
        replaceAgentDataScope(agentId, normalizedTenants, normalizedAccounts);
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        args.addAll(agentScopeArgs(agentId));
        jdbc.update(
                "UPDATE conversation c SET agent_id=NULL, status='need_human', " +
                        "queue_time=COALESCE(queue_time, now()), update_time=now() " +
                        "WHERE c.agent_id=? AND c.status='human' AND NOT " +
                        agentScopeCondition("c", "?"),
                args.toArray());
    }

    /** 超级管理员页面：列出超级管理员和普通管理员；权限只对普通管理员可编辑。 */
    public List<Map<String, Object>> listAdminAccounts() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, name, role, enabled, create_time FROM agent " +
                        "WHERE role IN ('super_admin', 'admin') " +
                        "ORDER BY CASE WHEN role = 'super_admin' THEN 0 ELSE 1 END, id");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String role = String.valueOf(row.get("role"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("username", row.get("username"));
            item.put("name", row.get("name"));
            item.put("role", role);
            item.put("enabled", row.get("enabled"));
            item.put("createTime", row.get("create_time"));
            item.put("permissions", permissionsFor(id, role));
            item.putAll(dataScopeFor(id, role));
            result.add(item);
        }
        return result;
    }

    @Transactional
    public long createAdminAccount(String username, String name, String password,
                                   List<String> permissions) {
        return createAdminAccount(username, name, password, permissions, List.of(), List.of());
    }

    @Transactional
    public long createAdminAccount(String username, String name, String password,
                                   List<String> permissions, List<Long> tenantIds,
                                   List<Long> kfAccountIds) {
        validateUsername(username);
        validatePassword(password);
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM agent WHERE username = ?",
                Integer.class, username.trim());
        if (existing != null && existing > 0) throw new IllegalArgumentException("账号已存在");
        String displayName = normalizeDisplayName(name, username);
        Long id = jdbc.queryForObject(
                "INSERT INTO agent(username, name, password, role, enabled) " +
                        "VALUES (?, ?, ?, 'admin', TRUE) RETURNING id",
                Long.class, username.trim(), displayName, encoder.encode(password));
        if (id == null) throw new IllegalStateException("管理员账号创建失败");
        replaceAdminPermissions(id, permissions);
        replaceAdminDataScope(id, tenantIds, kfAccountIds);
        return id;
    }

    @Transactional
    public void setAdminPermissions(long agentId, List<String> permissions) {
        requireOrdinaryAdmin(agentId);
        replaceAdminPermissions(agentId, permissions);
    }

    @Transactional
    public void setAdminDataScope(long agentId, List<Long> tenantIds, List<Long> kfAccountIds) {
        requireOrdinaryAdmin(agentId);
        replaceAdminDataScope(agentId, tenantIds, kfAccountIds);
    }

    /** 超级管理员分配数据范围时使用的企业/客服号目录。 */
    public Map<String, Object> adminDataScopeCatalog() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id, tenant_code AS code, name, enabled FROM wecom_tenant ORDER BY id");
        List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT id, tenant_id AS \"tenantId\", open_kfid AS \"openKfid\", name, enabled " +
                        "FROM wecom_kf_account ORDER BY tenant_id, id");
        return Map.of("tenants", tenants, "accounts", accounts);
    }

    /** 超级管理员修改自己或普通管理员的登录账号、显示名称。 */
    @Transactional
    public void updateAdminProfile(long agentId, String username, String name) {
        validateUsername(username);
        requireAdminAccount(agentId);
        String normalizedUsername = username.trim();
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM agent WHERE username = ? AND id <> ?",
                Integer.class, normalizedUsername, agentId);
        if (existing != null && existing > 0) throw new IllegalArgumentException("账号已存在");
        String displayName = normalizeDisplayName(name, normalizedUsername);
        int updated = jdbc.update(
                "UPDATE agent SET username = ?, name = ? " +
                        "WHERE id = ? AND role IN ('super_admin', 'admin')",
                normalizedUsername, displayName, agentId);
        if (updated == 0) throw new IllegalArgumentException("管理员账号不存在");
    }

    public void resetAdminPassword(long agentId, String password) {
        validatePassword(password);
        requireAdminAccount(agentId);
        int updated = jdbc.update(
                "UPDATE agent SET password = ? WHERE id = ? AND role IN ('super_admin', 'admin')",
                encoder.encode(password), agentId);
        if (updated == 0) throw new IllegalArgumentException("管理员账号不存在");
        stateStore.removeAllAuth(agentId);
    }

    public void setAdminEnabled(long agentId, boolean enabled) {
        requireOrdinaryAdmin(agentId);
        jdbc.update("UPDATE agent SET enabled = ?, status = CASE WHEN ? THEN status ELSE 'offline' END " +
                        "WHERE id = ? AND role = 'admin'",
                enabled, enabled, agentId);
        if (!enabled) stateStore.removeAllAuth(agentId);
    }

    private void replaceAdminPermissions(long agentId, List<String> permissions) {
        List<String> normalized = normalizePermissions(permissions);
        jdbc.update("DELETE FROM admin_permission WHERE agent_id = ?", agentId);
        for (String code : normalized) {
            jdbc.update("INSERT INTO admin_permission(agent_id, permission_code) VALUES (?, ?)",
                    agentId, code);
        }
    }

    private Map<String, Object> dataScopeFor(long agentId, String role) {
        if (ROLE_SUPER_ADMIN.equals(role)) {
            return Map.of(
                    "tenantIds", jdbc.queryForList("SELECT id FROM wecom_tenant ORDER BY id", Long.class),
                    "kfAccountIds", jdbc.queryForList("SELECT id FROM wecom_kf_account ORDER BY id", Long.class));
        }
        if (!ROLE_ADMIN.equals(role)) return Map.of("tenantIds", List.of(), "kfAccountIds", List.of());
        return Map.of(
                "tenantIds", jdbc.queryForList(
                        "SELECT tenant_id FROM admin_tenant_scope WHERE agent_id=? ORDER BY tenant_id",
                        Long.class, agentId),
                "kfAccountIds", jdbc.queryForList(
                        "SELECT kf_account_id FROM admin_kf_scope WHERE agent_id=? ORDER BY kf_account_id",
                        Long.class, agentId));
    }

    private Map<String, Object> agentDataScopeFor(long agentId) {
        return Map.of(
                "tenantIds", jdbc.queryForList(
                        "SELECT tenant_id FROM agent_tenant_scope WHERE agent_id=? ORDER BY tenant_id",
                        Long.class, agentId),
                "kfAccountIds", jdbc.queryForList(
                        "SELECT kf_account_id FROM agent_kf_scope WHERE agent_id=? ORDER BY kf_account_id",
                        Long.class, agentId));
    }

    private List<Map<String, Object>> enrichAgentScopes(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.putAll(agentDataScopeFor(((Number) row.get("id")).longValue()));
            result.add(item);
        }
        return result;
    }

    /** 普通管理员只能看到并管理完全落在其数据范围内的坐席账号。 */
    private String agentManagementFilter(String alias, List<Object> args) {
        AdminDataScope.AccessScope access = AdminDataScope.current();
        if (!access.restricted()) return "";
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        StringBuilder sql = new StringBuilder();
        if (access.tenantIds().isEmpty()) {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM agent_tenant_scope ats WHERE ats.agent_id=")
                    .append(prefix).append("id)");
        } else {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM agent_tenant_scope ats WHERE ats.agent_id=")
                    .append(prefix).append("id AND ats.tenant_id NOT IN (")
                    .append(placeholders(access.tenantIds().size())).append("))");
            args.addAll(access.tenantIds());
        }
        if (!access.kfAccountIds().isEmpty()) {
            // 坐席未指定客服号表示可服务其企业下全部客服号；账号级受限管理员不能管理这种更大范围。
            sql.append(" AND (NOT EXISTS (SELECT 1 FROM agent_tenant_scope ats0 WHERE ats0.agent_id=")
                    .append(prefix).append("id) OR (EXISTS (SELECT 1 FROM agent_kf_scope aks0 WHERE aks0.agent_id=")
                    .append(prefix).append("id) AND NOT EXISTS (SELECT 1 FROM agent_kf_scope aks WHERE aks.agent_id=")
                    .append(prefix).append("id AND aks.kf_account_id NOT IN (")
                    .append(placeholders(access.kfAccountIds().size())).append("))))");
            args.addAll(access.kfAccountIds());
        }
        return sql.toString();
    }

    private void requireManageableAgent(long agentId) {
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        String filter = agentManagementFilter("a", args);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent a WHERE a.id=? AND a.role='agent'" + filter,
                Integer.class, args.toArray());
        if (count == null || count == 0) {
            throw new IllegalArgumentException("客服账号不存在或不在当前管理员的数据范围内");
        }
    }

    private void replaceAgentDataScope(long agentId, List<Long> tenantIds, List<Long> kfAccountIds) {
        jdbc.update("DELETE FROM agent_kf_scope WHERE agent_id=?", agentId);
        jdbc.update("DELETE FROM agent_tenant_scope WHERE agent_id=?", agentId);
        for (Long tenantId : tenantIds) {
            jdbc.update("INSERT INTO agent_tenant_scope(agent_id, tenant_id) VALUES (?,?)",
                    agentId, tenantId);
        }
        for (Long accountId : kfAccountIds) {
            jdbc.update("INSERT INTO agent_kf_scope(agent_id, kf_account_id) VALUES (?,?)",
                    agentId, accountId);
        }
    }

    private void replaceAdminDataScope(long agentId, List<Long> tenantIds, List<Long> kfAccountIds) {
        List<Long> normalizedTenants = normalizeIds(tenantIds);
        List<Long> normalizedAccounts = normalizeIds(kfAccountIds);
        validateDataScope(normalizedTenants, normalizedAccounts);
        jdbc.update("DELETE FROM admin_kf_scope WHERE agent_id=?", agentId);
        jdbc.update("DELETE FROM admin_tenant_scope WHERE agent_id=?", agentId);
        for (Long tenantId : normalizedTenants) {
            jdbc.update("INSERT INTO admin_tenant_scope(agent_id, tenant_id) VALUES (?,?)",
                    agentId, tenantId);
        }
        for (Long accountId : normalizedAccounts) {
            jdbc.update("INSERT INTO admin_kf_scope(agent_id, kf_account_id) VALUES (?,?)",
                    agentId, accountId);
        }
    }

    private void validateDataScope(List<Long> tenantIds, List<Long> kfAccountIds) {
        if (tenantIds.isEmpty() && !kfAccountIds.isEmpty()) {
            throw new IllegalArgumentException("分配客服号前必须先选择所属企业");
        }
        if (!tenantIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(tenantIds.size(), "?"));
            List<Long> existing = jdbc.queryForList(
                    "SELECT id FROM wecom_tenant WHERE id IN (" + placeholders + ")",
                    Long.class, tenantIds.toArray());
            if (existing.size() != tenantIds.size()) throw new IllegalArgumentException("数据范围包含不存在的企业");
        }
        if (!kfAccountIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(kfAccountIds.size(), "?"));
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, tenant_id FROM wecom_kf_account WHERE id IN (" + placeholders + ")",
                    kfAccountIds.toArray());
            if (rows.size() != kfAccountIds.size()) throw new IllegalArgumentException("数据范围包含不存在的客服号");
            for (Map<String, Object> row : rows) {
                long tenantId = ((Number) row.get("tenant_id")).longValue();
                if (!tenantIds.contains(tenantId)) {
                    throw new IllegalArgumentException("客服号必须属于已选择的企业范围");
                }
            }
        }
    }

    private void validateAgentDataScope(List<Long> tenantIds, List<Long> kfAccountIds) {
        validateDataScope(tenantIds, kfAccountIds);
        AdminDataScope.AccessScope access = AdminDataScope.current();
        if (!access.restricted()) return;
        if (!access.tenantIds().containsAll(tenantIds)) {
            throw new IllegalArgumentException("不能把客服分配到当前管理员范围外的企业");
        }
        if (!access.kfAccountIds().isEmpty()) {
            if (!tenantIds.isEmpty() && kfAccountIds.isEmpty()) {
                throw new IllegalArgumentException("当前管理员按客服号受限，必须为客服明确选择可服务的客服号");
            }
            if (!access.kfAccountIds().containsAll(kfAccountIds)) {
                throw new IllegalArgumentException("不能把客服分配到当前管理员范围外的客服号");
            }
        }
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null) return List.of();
        return ids.stream().filter(java.util.Objects::nonNull).filter(id -> id > 0)
                .distinct().sorted().toList();
    }

    private List<String> normalizePermissions(List<String> permissions) {
        Set<String> requested = new LinkedHashSet<>(permissions == null ? List.of() : permissions);
        for (String code : requested) {
            if (!AdminPermissionCatalog.isKnown(code)) {
                throw new IllegalArgumentException("不支持的权限：" + code);
            }
        }
        return AdminPermissionCatalog.codes().stream().filter(requested::contains).toList();
    }

    private void requireOrdinaryAdmin(long agentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent WHERE id = ? AND role = 'admin'",
                Integer.class, agentId);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("普通管理员账号不存在；超级管理员不可在此修改");
        }
    }

    private void requireAdminAccount(long agentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent WHERE id = ? AND role IN ('super_admin', 'admin')",
                Integer.class, agentId);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("管理员账号不存在");
        }
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    /** tenant_id 为空的是网页/全局会话；企微数据必须命中坐席企业及可选客服号范围。 */
    private String agentScopeCondition(String alias, String agentIdExpression) {
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return "(" + prefix + "tenant_id IS NULL OR (" +
                "EXISTS (SELECT 1 FROM agent_tenant_scope ats WHERE ats.agent_id=" + agentIdExpression +
                " AND ats.tenant_id=" + prefix + "tenant_id) AND (" +
                prefix + "kf_account_id IS NULL OR " +
                "NOT EXISTS (SELECT 1 FROM agent_kf_scope aks_any WHERE aks_any.agent_id=" + agentIdExpression + ") OR " +
                "EXISTS (SELECT 1 FROM agent_kf_scope aks_match WHERE aks_match.agent_id=" + agentIdExpression +
                " AND aks_match.kf_account_id=" + prefix + "kf_account_id))))";
    }

    private List<Object> agentScopeArgs(long agentId) {
        return List.of(agentId, agentId, agentId);
    }

    private String normalizeDisplayName(String name, String username) {
        String displayName = name == null || name.isBlank() ? username.trim() : name.trim();
        if (displayName.length() > 50) {
            throw new IllegalArgumentException("显示名称不能超过 50 个字符");
        }
        return displayName;
    }

    private void validateUsername(String username) {
        if (username == null || !username.trim().matches("[A-Za-z0-9._-]{3,50}")) {
            throw new IllegalArgumentException("账号需为 3-50 位字母、数字、点、下划线或短横线");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码至少 8 位");
        }
    }

    /**
     * 授权池轮询分配：从可服务该企业/客服号的在线坐席中找"最久没接单"者。
     * 原子化：条件 UPDATE 以受影响行数判断分配成功，避免并发抢同一会话/覆盖已有坐席。
     * 幽灵坐席修复：候选坐席必须 WS 真的在线（SessionRegistry 校验），
     * 数据库 status 为 online 但 WS 未连接的坐席不参与分配（否则客户消息会静默丢失）。
     * @return 坐席展示名；没有可用的在线坐席或会话已被他人接管返回 null（客户进排队）
     */
    public String tryAssignRoundRobin(String conversationId) {
        // 数据库先按会话归属筛掉越权坐席，再按"最久没接单"排序并校验 WS 在线。
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT a.id, a.name FROM agent a JOIN conversation c ON c.conversation_id=? " +
                        "WHERE a.role='agent' AND a.enabled=TRUE AND a.status='online' AND " +
                        agentScopeCondition("c", "a.id") +
                        " ORDER BY a.last_assigned_time NULLS FIRST",
                conversationId);
        long agentId = -1;
        String name = null;
        for (Map<String, Object> a : candidates) {
            long id = ((Number) a.get("id")).longValue();
            if (sessionRegistry.isAgentOnline(id)) {
                agentId = id;
                name = String.valueOf(a.get("name"));
                break;
            }
        }
        if (agentId < 0) {
            return null;
        }
        // 条件更新：仅当会话尚未分配给任何坐席时才接管；受影响行数 0 说明已被别人抢先
        List<Object> assignArgs = new ArrayList<>();
        assignArgs.add(agentId);
        assignArgs.add(conversationId);
        assignArgs.addAll(agentScopeArgs(agentId));
        int n = jdbc.update(
                "UPDATE conversation c SET agent_id = ?, status = 'human', " +
                        "queue_time = COALESCE(queue_time, now()), update_time = now() " +
                        "WHERE c.conversation_id = ? AND c.agent_id IS NULL " +
                        "AND c.status IN ('active', 'need_human') AND " +
                        agentScopeCondition("c", "?"),
                assignArgs.toArray());
        if (n == 0) {
            // 已被他人抢先接管：若会话当前已有人工坐席在服务，则告知该坐席名（不降级、不覆盖）
            List<Long> serving = jdbc.queryForList(
                    "SELECT agent_id FROM conversation WHERE conversation_id = ? AND status = 'human' AND agent_id IS NOT NULL",
                    Long.class, conversationId);
            if (!serving.isEmpty()) {
                return getAgentName(serving.get(0));
            }
            return null;
        }
        jdbc.update("UPDATE agent SET last_assigned_time = now() WHERE id = ?", agentId);
        return name;
    }

    /** 排队队列：等待人工的会话（先进先出，按入队时间排序；wait_min 为已等待分钟数，前端用于高亮超时） */
    public List<Map<String, Object>> queue(long agentId) {
        List<Object> args = agentScopeArgs(agentId);
        return jdbc.queryForList(
                "SELECT c.conversation_id, c.user_id, c.update_time, " + CUSTOMER_NAME_SQL + ", " +
                "  EXTRACT(EPOCH FROM (now() - COALESCE(c.queue_time, c.update_time)))/60 AS wait_min, " +
                "  (SELECT content FROM message m WHERE m.conversation_id = c.conversation_id " +
                "    AND m.role = 'user' ORDER BY create_time DESC, id DESC LIMIT 1) AS last_question " +
                "FROM conversation c WHERE c.status = 'need_human' AND " +
                        agentScopeCondition("c", "?") + " " +
                "ORDER BY COALESCE(c.queue_time, c.update_time) NULLS LAST, c.id",
                args.toArray());
    }

    /** 坐席手动从队列接入（条件更新，两个坐席同时抢同一会话时只有一个成功） */
    public boolean take(String conversationId, long agentId) {
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        args.add(conversationId);
        args.addAll(agentScopeArgs(agentId));
        int n = jdbc.update(
                "UPDATE conversation c SET agent_id = ?, status = 'human', update_time = now() " +
                        "WHERE c.conversation_id = ? AND c.status = 'need_human' " +
                        "AND c.agent_id IS NULL AND " + agentScopeCondition("c", "?"),
                args.toArray());
        if (n > 0) {
            jdbc.update("UPDATE agent SET last_assigned_time = now() WHERE id = ?", agentId);
        }
        return n > 0;
    }

    /** 坐席正在服务的会话 */
    public List<Map<String, Object>> myConversations(long agentId) {
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        args.addAll(agentScopeArgs(agentId));
        return jdbc.queryForList(
                "SELECT c.conversation_id, c.user_id, c.update_time, " + CUSTOMER_NAME_SQL + ", " +
                "  (SELECT content FROM message m WHERE m.conversation_id = c.conversation_id " +
                "    AND m.role = 'user' ORDER BY create_time DESC, id DESC LIMIT 1) AS last_question " +
                "FROM conversation c WHERE c.status = 'human' AND c.agent_id = ? AND " +
                        agentScopeCondition("c", "?") +
                        " ORDER BY c.update_time DESC, c.id DESC",
                args.toArray());
    }

    /** 结束会话：状态关闭，客户再发消息将回到 AI */
    public boolean close(String conversationId, long agentId) {
        List<Object> args = new ArrayList<>();
        args.add(conversationId);
        args.add(agentId);
        args.addAll(agentScopeArgs(agentId));
        return jdbc.update(
                "UPDATE conversation c SET status = 'closed', update_time = now() " +
                        "WHERE c.conversation_id = ? AND c.agent_id = ? AND c.status = 'human' AND " +
                        agentScopeCondition("c", "?"),
                args.toArray()) > 0;
    }

    /** 会话当前归属的坐席 ID（客户消息转发用） */
    public Long getAgentOf(String conversationId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT agent_id FROM conversation WHERE conversation_id = ? AND status = 'human'",
                Long.class, conversationId);
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0);
    }

    /**
     * 会话转接（P2-12）：当前服务坐席把 human 会话移交给目标坐席。
     * 目标坐席必须 enabled + agent 角色 + DB online + WS 真在线（与轮询分配同一套幽灵坐席双校验），
     * 且必须在该会话的数据范围内（agentScopeCondition）。
     * @return 目标坐席展示名；会话状态已变化或目标不可用时返回 null
     */
    public String transfer(String conversationId, long fromAgentId, long targetAgentId) {
        if (fromAgentId == targetAgentId) return null;
        List<Map<String, Object>> targets = jdbc.queryForList(
                "SELECT a.id, a.name FROM agent a JOIN conversation c ON c.conversation_id = ? " +
                        "WHERE a.id = ? AND a.role='agent' AND a.enabled=TRUE AND a.status='online' AND " +
                        agentScopeCondition("c", "a.id"),
                conversationId, targetAgentId);
        if (targets.isEmpty()) return null;
        long targetId = ((Number) targets.get(0).get("id")).longValue();
        String name = String.valueOf(targets.get(0).get("name"));
        if (!sessionRegistry.isAgentOnline(targetId)) return null;
        List<Object> args = new ArrayList<>();
        args.add(targetId);
        args.add(conversationId);
        args.add(fromAgentId);
        args.addAll(agentScopeArgs(targetId));
        int n = jdbc.update(
                "UPDATE conversation c SET agent_id = ?, update_time = now() " +
                        "WHERE c.conversation_id = ? AND c.agent_id = ? AND c.status = 'human' AND " +
                        agentScopeCondition("c", "?"),
                args.toArray());
        if (n == 0) return null;
        jdbc.update("UPDATE agent SET last_assigned_time = now() WHERE id = ?", targetId);
        return name;
    }

    /** 在线坐席列表（转接选择用，不含自己）：DB online + WS 真在线双口径，含服务中会话数。 */
    public List<Map<String, Object>> onlineAgents(long excludeAgentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.id, a.name, " +
                        "(SELECT count(*) FROM conversation c WHERE c.agent_id = a.id AND c.status = 'human') AS serving " +
                        "FROM agent a WHERE a.status = 'online' AND a.enabled = TRUE AND a.role = 'agent' AND a.id <> ? " +
                        "ORDER BY a.name",
                excludeAgentId);
        return rows.stream()
                .filter(row -> sessionRegistry.isAgentOnline(((Number) row.get("id")).longValue()))
                .toList();
    }

    /** 会话退回排队队列（转接通知失败时兜底，避免会话悬空无人服务）。 */
    public void releaseToQueue(String conversationId) {
        jdbc.update(
                "UPDATE conversation c SET agent_id = NULL, status = 'need_human', " +
                        "queue_time = COALESCE(queue_time, now()), update_time = now() " +
                        "WHERE c.conversation_id = ? AND c.status = 'human'",
                conversationId);
    }

    /** 校验某坐席是否正在服务该会话（坐席发消息前的越权拦截，修复 P0-4） */
    public boolean isServing(long agentId, String conversationId) {
        List<Object> args = new ArrayList<>();
        args.add(conversationId);
        args.add(agentId);
        args.addAll(agentScopeArgs(agentId));
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM conversation c WHERE c.conversation_id = ? " +
                        "AND c.agent_id = ? AND c.status = 'human' AND " + agentScopeCondition("c", "?"),
                Integer.class, args.toArray());
        return n != null && n > 0;
    }

    /**
     * 预览权限：排队中（need_human）的会话，任何坐席都可先看消息历史再决定是否接入。
     * 接入后以 isServing 为准（独占服务权）。预览为只读，不能发消息。
     */
    public boolean isPreviewable(long agentId, String conversationId) {
        // 必须与 queue/mine/take/close 用同一套 agentScopeCondition：否则被授权企业甲的坐席
        // 拿到企业乙的排队会话 ID 就能预览其完整聊天记录（跨租户越权读）。
        List<Object> args = new ArrayList<>();
        args.add(conversationId);
        args.addAll(agentScopeArgs(agentId));
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM conversation c WHERE c.conversation_id = ? AND c.status = 'need_human'"
                        + " AND " + agentScopeCondition("c", "?"),
                Integer.class, args.toArray());
        return n != null && n > 0;
    }

    /** 仅返回当前坐席正在服务的这个会话所属范围内的客户统计。 */
    public Map<String, Object> customerStats(long agentId, String conversationId, String userId) {
        List<Object> routeArgs = new ArrayList<>();
        routeArgs.add(conversationId);
        routeArgs.add(userId);
        routeArgs.add(agentId);
        routeArgs.addAll(agentScopeArgs(agentId));
        List<Map<String, Object>> routes = jdbc.queryForList(
                "SELECT c.tenant_id, c.kf_account_id FROM conversation c " +
                        "WHERE c.conversation_id=? AND c.user_id=? AND c.agent_id=? " +
                        "AND c.status='human' AND " + agentScopeCondition("c", "?"),
                routeArgs.toArray());
        if (routes.isEmpty()) return null;

        Object tenantValue = routes.get(0).get("tenant_id");
        Object accountValue = routes.get(0).get("kf_account_id");
        String scopeSql;
        List<Object> scopeArgs = new ArrayList<>();
        if (!(tenantValue instanceof Number tenant)) {
            scopeSql = "tenant_id IS NULL";
        } else if (!(accountValue instanceof Number account)) {
            scopeSql = "tenant_id=?";
            scopeArgs.add(tenant.longValue());
        } else {
            scopeSql = "tenant_id=? AND kf_account_id=?";
            scopeArgs.add(tenant.longValue());
            scopeArgs.add(account.longValue());
        }

        List<Object> conversationArgs = new ArrayList<>();
        conversationArgs.add(userId);
        conversationArgs.addAll(scopeArgs);
        Integer convCount = jdbc.queryForObject(
                "SELECT count(*) FROM conversation WHERE user_id=? AND " + scopeSql,
                Integer.class, conversationArgs.toArray());

        List<Object> appointmentArgs = new ArrayList<>();
        appointmentArgs.add(userId);
        appointmentArgs.addAll(scopeArgs);
        List<Map<String, Object>> appointments = jdbc.queryForList(
                "SELECT name, phone, project, city, status, create_time FROM appointment " +
                        "WHERE user_id=? AND " + scopeSql +
                        " ORDER BY create_time DESC, id DESC LIMIT 1",
                appointmentArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationCount", convCount == null ? 0 : convCount);
        result.put("lastAppointment", appointments.isEmpty() ? null : appointments.get(0));
        return result;
    }

    /** 坐席展示名 */
    public String getAgentName(long agentId) {
        List<String> rows = jdbc.queryForList(
                "SELECT name FROM agent WHERE id = ?", String.class, agentId);
        return rows.isEmpty() ? "人工客服" : rows.get(0);
    }

    /**
     * 按会话解析客户展示名（与 queue/mine 列表的 user_name 同一口径），
     * 供坐席读消息时把名字回填到每条消息上，聊天区头像/顶栏与列表一致。
     * 网页匿名访客无企微昵称也无预约姓名时返回 null，前端回退显示「访客」。
     */
    public String resolveCustomerName(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + CUSTOMER_NAME_SQL + " FROM conversation c WHERE c.conversation_id = ?",
                conversationId);
        if (rows.isEmpty()) return null;
        Object name = rows.get(0).get("user_name");
        return name == null ? null : String.valueOf(name);
    }
}
