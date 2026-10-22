package com.example.aics.image;

import com.example.aics.channel.TenantExecutionContext;
import com.example.aics.config.ScopedDataAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 图片素材库：本地存储 + 标签检索（"发送图片"功能的基础）。
 *
 * 存储：文件落在项目目录 uploads/images/ 下，数据库 image_asset 表记元数据；
 * 访问：/uploads/** 已映射为静态资源（见 WebConfig）；
 * 检索：按标签匹配——客户消息里出现图片的标签词，这张图就会被选中发出去。
 *      图片量小（几十~几百张），Java 内存匹配足够，不上向量。
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private final JdbcTemplate jdbc;
    private final Path imageDir;

    /** 允许上传的图片扩展名白名单（防上传 html/svg 等静态可执行文件，P1-10） */
    private static final java.util.Set<String> ALLOWED_EXTENSIONS =
            java.util.Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 企微临时媒体上传的图片大小上限。 */
    private static final long WECOM_IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    private static final long CHAT_IMAGE_MAX_PIXELS = 24_000_000L;

    /** 发送到外部渠道所需的原始图片；只在内存中短暂存在，不暴露本地文件路径。 */
    public record OutboundImage(String filename, String contentType, byte[] bytes) {}

    public ImageService(JdbcTemplate jdbc,
                        @Value("${file.upload-dir:uploads}") String uploadDir) throws IOException {
        this.jdbc = jdbc;
        this.imageDir = Paths.get(uploadDir, "images");
        Files.createDirectories(this.imageDir);
    }

    /** 上传一张图片：存文件 + 记元数据，返回可访问的 url */
    public Map<String, Object> save(MultipartFile file, String name, String tags,
                                    String project, String assetType, Boolean enabled,
                                    String validFrom, String validUntil) throws IOException {
        String original = file.getOriginalFilename() == null ? "img.png" : file.getOriginalFilename();
        // 无扩展名或扩展名不在白名单 → 400 拒绝（避免 substring(-1) 越界与上传非图片文件）
        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase() : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("仅支持图片格式：" + ALLOWED_EXTENSIONS);
        }
        byte[] bytes = file.getBytes();
        validateImageBytes(bytes, ext, 10L * 1024 * 1024);
        String fileName = UUID.randomUUID() + "." + ext;
        Path target = imageDir.resolve(fileName);
        Files.write(target, bytes);

        String url = "/uploads/images/" + fileName;
        String imageName = (name == null || name.isBlank()) ? original : name;
        String normalizedType = normalizeAssetType(assetType);
        LocalDateTime from = parseDateTime(validFrom);
        LocalDateTime until = parseDateTime(validUntil);
        validateValidity(from, until);
        TenantExecutionContext.Scope scope = TenantExecutionContext.current();
        Long tenantId = scope == null ? null : scope.tenantId();
        Long kfAccountId = scope == null ? null : scope.kfAccountId();
        Long imageId = jdbc.queryForObject(
                "INSERT INTO image_asset(tenant_id,kf_account_id,name,tags,project,asset_type,enabled,valid_from,valid_until,file_path,url) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                tenantId, kfAccountId, imageName, tags == null ? "" : tags, blankToNull(project), normalizedType,
                enabled == null || enabled, from, until, target.toString(), url);
        if (imageId != null) syncProjectLink(imageId, project);
        return Map.of("name", imageName, "url", url);
    }

    /** 保留旧调用签名：旧接口上传的素材默认作为启用的效果图。 */
    public Map<String, Object> save(MultipartFile file, String name, String tags) throws IOException {
        return save(file, name, tags, null, "effect", true, null, null);
    }

    /**
     * 客户聊天发图（W26-U1）：只存文件到 uploads/images/ 供前端展示，不入 image_asset 素材库。
     * 文件名加 "c_" 前缀区分客户图，避免与素材库图片混淆。
     * @return Map(url) 可访问的图片地址
     */
    public Map<String, Object> saveCustomerImage(MultipartFile file, String userId) throws IOException {
        String original = file.getOriginalFilename() == null ? "img.png" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase() : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("仅支持图片格式：" + ALLOWED_EXTENSIONS);
        }
        byte[] bytes = file.getBytes();
        validateImageBytes(bytes, ext, 8L * 1024 * 1024);
        String fileName = "c_" + UUID.randomUUID() + "." + ext;
        Files.write(imageDir.resolve(fileName), bytes);
        jdbc.update("INSERT INTO customer_uploaded_image(user_id, filename) VALUES (?, ?)",
                userId, fileName);
        return Map.of("url", "/uploads/images/" + fileName);
    }

    /**
     * 坐席工作台发图：与客户图同一目录存储（a_ 前缀区分坐席图），
     * 不入 image_asset 素材库、不进 customer_uploaded_image 客户归属表。
     * @return Map(url) 可访问的图片地址
     */
    public Map<String, Object> saveAgentImage(MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "img.png" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase() : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("仅支持图片格式：" + ALLOWED_EXTENSIONS);
        }
        byte[] bytes = file.getBytes();
        validateImageBytes(bytes, ext, 8L * 1024 * 1024);
        String fileName = "a_" + UUID.randomUUID() + "." + ext;
        Files.write(imageDir.resolve(fileName), bytes);
        return Map.of("url", "/uploads/images/" + fileName, "name", original);
    }

    /** 按文件名安全读取 uploads/images/ 下已上传的聊天图片（坐席发企微时转临时媒体用）。 */
    public OutboundImage loadUploadedByName(String filename) throws IOException {
        if (filename == null || !filename.matches("^[ac]_[A-Za-z0-9._-]+$")) {
            throw new IllegalArgumentException("非法图片文件名");
        }
        java.nio.file.Path path = imageDir.resolve(filename).normalize();
        if (!path.startsWith(imageDir)) {
            throw new IllegalArgumentException("非法图片路径");
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        String contentType = switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/jpeg";
        };
        return new OutboundImage(filename, contentType, Files.readAllBytes(path));
    }

    /** 在外层事务中移除客户图片归属记录，并返回提交后需要删除的安全文件名。 */
    public List<String> removeCustomerImageRecords(String userId) {
        List<String> filenames = jdbc.queryForList(
                "SELECT filename FROM customer_uploaded_image WHERE user_id = ?",
                String.class, userId).stream()
                .filter(name -> name != null && name.matches("c_[A-Za-z0-9._-]{1,253}"))
                .toList();
        jdbc.update("DELETE FROM customer_uploaded_image WHERE user_id = ?", userId);
        return filenames;
    }

    /** 只删除 removeCustomerImageRecords 返回的 c_ 客户文件；数据库事务提交后调用。 */
    public void deleteCustomerImageFiles(List<String> filenames) {
        if (filenames == null) return;
        Path root = imageDir.normalize().toAbsolutePath();
        for (String filename : filenames) {
            if (filename == null || !filename.matches("c_[A-Za-z0-9._-]{1,253}")) continue;
            Path target = imageDir.resolve(filename).normalize().toAbsolutePath();
            if (!target.startsWith(root)) continue;
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                log.warn("删除客户图片失败 filename={}: {}", filename, e.getMessage());
            }
        }
    }

    /** 受控网页读取：客户图必须属于当前 Cookie 身份；运营素材和历史已发送素材可公开展示。 */
    public Resource loadWebImage(String filename, String userId) throws IOException {
        if (filename == null || !filename.matches("[A-Za-z0-9._-]{1,255}")) {
            throw new IllegalArgumentException("图片文件名不正确");
        }
        if (filename.startsWith("c_")) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM customer_uploaded_image WHERE filename = ? AND user_id = ?",
                    Integer.class, filename, userId);
            if (count == null || count == 0) return null;
        }
        Path resolved = imageDir.resolve(filename).normalize();
        if (!resolved.startsWith(imageDir.normalize()) || !Files.isRegularFile(resolved)) return null;
        return new UrlResource(resolved.toUri());
    }

    /** 兼容测试和内部旧调用；正式客户入口必须传服务端身份。 */
    @Deprecated
    public Map<String, Object> saveCustomerImage(MultipartFile file) throws IOException {
        return saveCustomerImage(file, "anonymous");
    }

    private void validateImageBytes(byte[] bytes, String extension, long maxBytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("图片文件为空");
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("图片过大，请压缩后再上传");
        }
        if (!magicMatches(bytes, extension)) {
            throw new IllegalArgumentException("文件内容与图片格式不一致");
        }
        // 先只读图片头部声明的宽高做像素校验：ImageIO.read 会按头部尺寸一次性分配整张位图，
        // 「体积很小但声明尺寸极大」的解压炸弹若先解码会直接把堆撑爆（客户发图接口未登录可达）。
        long[] declared = readDeclaredDimensions(bytes);
        if (declared != null && declared[0] * declared[1] > CHAT_IMAGE_MAX_PIXELS) {
            throw new IllegalArgumentException("图片像素过大，请压缩到 2400 万像素以内");
        }
        try (var input = new java.io.ByteArrayInputStream(bytes)) {
            var image = ImageIO.read(input);
            // GIF/WebP 在部分 JDK 没有解码插件，魔数校验通过时允许；常用 JPEG/PNG/BMP 必须可解码。
            if (image == null && !Set.of("gif", "webp").contains(extension)) {
                throw new IllegalArgumentException("图片已损坏或格式不受支持");
            }
            if (image != null && (long) image.getWidth() * image.getHeight() > CHAT_IMAGE_MAX_PIXELS) {
                throw new IllegalArgumentException("图片像素过大，请压缩到 2400 万像素以内");
            }
        }
    }

    /**
     * 只解析图片头部拿到声明宽高，不解码像素数据（代价极低，用于在真正解码前拦下超大尺寸图片）。
     * 读不到头部（未知格式/无解码插件）时返回 null，交由调用方走原有解码校验路径。
     */
    private long[] readDeclaredDimensions(byte[] bytes) {
        try (var stream = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (stream == null) return null;
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return new long[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private boolean magicMatches(byte[] b, String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
            case "png" -> b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47;
            case "gif" -> b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
            case "bmp" -> b.length >= 2 && b[0] == 'B' && b[1] == 'M';
            case "webp" -> b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            default -> false;
        };
    }

    /** 全部图片（管理列表用） */
    public List<Map<String, Object>> list() {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("i");
        String projectList = "COALESCE((SELECT string_agg(p.name,',' ORDER BY pa.sort_order,p.sort_order,p.id) " +
                "FROM project_asset pa JOIN service_project p ON p.id=pa.project_id " +
                "WHERE pa.image_id=i.id AND p.status<>'disabled'),i.project) AS projects";
        if (!scope.active()) return jdbc.queryForList(
                "SELECT i.id,i.tenant_id,i.kf_account_id,i.name,i.tags,i.project,i.asset_type,i.enabled," +
                        "i.valid_from,i.valid_until,i.url,i.create_time," + projectList +
                        " FROM image_asset i ORDER BY i.id DESC");
        return jdbc.queryForList(
                "SELECT i.id, i.tenant_id, i.kf_account_id, i.name, i.tags, i.project, i.asset_type, i.enabled, i.valid_from, " +
                        "i.valid_until, i.url, i.create_time," + projectList + " FROM image_asset i WHERE " + scope.sql() +
                        " ORDER BY i.id DESC", scope.args().toArray());
    }

    /**
     * 读取一张当前可用的素材，用于上传到企微后立即发送真实图片消息。
     * 数据库中的 file_path 可能来自另一部署目录，因此文件不存在时会按文件名回退到当前 uploads/images。
     */
    public OutboundImage loadForExternalSend(long imageId) throws IOException {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("i");
        if (!scope.active()) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT file_path FROM image_asset WHERE id = ? AND enabled = TRUE " +
                            "AND (valid_from IS NULL OR valid_from <= now()) " +
                            "AND (valid_until IS NULL OR valid_until >= now())", imageId);
            return readOutboundImage(rows);
        }
        List<Object> args = new ArrayList<>(List.of(imageId));
        args.addAll(scope.args());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.file_path FROM image_asset i WHERE i.id = ? AND i.enabled = TRUE " +
                        "AND (valid_from IS NULL OR valid_from <= now()) " +
                        "AND (valid_until IS NULL OR valid_until >= now())" +
                        (scope.active() ? " AND " + scope.sql() : ""), args.toArray());
        return readOutboundImage(rows);
    }

    private OutboundImage readOutboundImage(List<Map<String, Object>> rows) throws IOException {
        if (rows.isEmpty()) throw new IllegalArgumentException("图片素材不存在、已停用或不在有效期内");
        String storedPath = stringValue(rows.get(0).get("file_path"));
        if (storedPath.isBlank()) throw new IllegalArgumentException("图片素材缺少文件路径");

        Path configured = Paths.get(storedPath);
        Path candidate = configured.isAbsolute() ? configured : configured.toAbsolutePath();
        if (!Files.isRegularFile(candidate)) {
            candidate = imageDir.resolve(configured.getFileName());
        }
        if (!Files.isRegularFile(candidate)) throw new IllegalArgumentException("图片素材文件不存在");

        Path root = imageDir.toRealPath();
        Path realFile = candidate.toRealPath();
        if (!realFile.startsWith(root)) throw new IllegalArgumentException("图片素材文件路径不安全");
        long size = Files.size(realFile);
        if (size <= 0 || size > WECOM_IMAGE_MAX_BYTES) {
            throw new IllegalArgumentException("企微图片必须大于 0 且不超过 10MB");
        }
        String filename = realFile.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        String ext = extensionOf(filename);
        String contentType = switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> throw new IllegalArgumentException("不支持发送该图片格式");
        };
        return new OutboundImage(filename, contentType, Files.readAllBytes(realFile));
    }

    /** 删除（删记录 + 删本地文件） */
    public int delete(long id) {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("i");
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(scope.args());
        List<String> paths = jdbc.queryForList(
                "SELECT i.file_path FROM image_asset i WHERE i.id = ?" +
                        (scope.active() ? " AND " + scope.sql() : ""), String.class, args.toArray());
        if (paths.isEmpty()) return 0;
        Integer historicalUses = jdbc.queryForObject(
                "SELECT count(*) FROM message_attachment WHERE asset_id = ?", Integer.class, id);
        int n = jdbc.update("DELETE FROM image_asset i WHERE i.id = ?" +
                (scope.active() ? " AND " + scope.sql() : ""), args.toArray());
        // 已经发给客户的素材必须长期可查看；删除素材库记录时保留其物理文件。
        if (historicalUses == null || historicalUses == 0) {
            for (String p : paths) {
                try { Files.deleteIfExists(Paths.get(p)); } catch (IOException ignored) {}
            }
        }
        return n;
    }

    /** 更新图片名称/标签（管理端编辑用，不涉及文件本身） */
    public int update(long id, String name, String tags, String project, String assetType,
                      Boolean enabled, String validFrom, String validUntil) {
        LocalDateTime from = parseDateTime(validFrom);
        LocalDateTime until = parseDateTime(validUntil);
        validateValidity(from, until);
        StringBuilder sql = new StringBuilder("UPDATE image_asset i SET name = ?, tags = ?, project = ?, asset_type = ?, " +
                "enabled = ?, valid_from = ?, valid_until = ? WHERE id = ?");
        List<Object> args = new ArrayList<>();
        args.add(name == null ? "" : name);
        args.add(tags == null ? "" : tags);
        args.add(blankToNull(project));
        args.add(normalizeAssetType(assetType));
        args.add(enabled == null || enabled);
        args.add(from);
        args.add(until);
        args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "i");
        int updated = jdbc.update(sql.toString(), args.toArray());
        if (updated > 0) syncProjectLink(id, project);
        return updated;
    }

    /** 保留旧调用签名。 */
    public int update(long id, String name, String tags) {
        StringBuilder sql = new StringBuilder("UPDATE image_asset i SET name = ?, tags = ? WHERE id = ?");
        List<Object> args = new ArrayList<>();
        args.add(name == null ? "" : name);
        args.add(tags == null ? "" : tags);
        args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "i");
        return jdbc.update(sql.toString(), args.toArray());
    }

    /** 按客户消息匹配要发送的图片：消息里命中了某张图的标签词，就带上它。
     * 例：图片标签"眼袋 对比图"，客户说"有眼袋的对比图吗"→ 命中"眼袋"和"对比图"。
     */
    public List<Map<String, Object>> matchByMessage(String message) {
        return matchByRequest(message, null);
    }

    /** 客户明确索图时，结合本轮/历史识别出的项目，只发送客户要求的图片类型。 */
    public List<Map<String, Object>> matchByRequest(String message, String project) {
        return matchByRequest(message, project, null);
    }

    /** 客户后续主动索图：每次只选择一张本会话尚未发送过的图片。 */
    public List<Map<String, Object>> matchByRequest(String message, String project, String conversationId) {
        return matchByRequest(message, project, conversationId, 1, true);
    }

    /** 热配置版本：可调整单次发送数量及是否排除本会话已经发送过的素材。 */
    public List<Map<String, Object>> matchByRequest(String message, String project, String conversationId,
                                                    int limit, boolean avoidRepeat) {
        boolean wantsPromo = containsAny(message, "海报", "优惠图", "活动图", "活动海报");
        String requestedType = wantsPromo ? "promo" : "effect";
        Set<Long> sentIds = avoidRepeat ? sentImageIds(conversationId) : Set.of();
        List<Map<String, Object>> all = list();
        return all.stream()
                .filter(this::isEnabled)
                .filter(img -> isWithinValidity(img, LocalDateTime.now()))
                .filter(img -> requestedType.equals(assetTypeOf(img)))
                .filter(img -> !sentIds.contains(imageId(img)))
                .filter(img -> project == null || project.isBlank()
                        ? matchesAnyTag(message, String.valueOf(img.get("tags")))
                        : matchesProject(img, project))
                .limit(Math.max(1, Math.min(limit, 5)))
                .map(this::toChatImage)
                .toList();
    }

    /**
     * 同一项目首次发送 sale_copy 时自动附图：最多一张效果图 + 一张当前有效的优惠海报。
     * 这里只从后台启用的素材中选择；后续轮次不会调用本方法。
     */
    public List<Map<String, Object>> matchInitialProjectAssets(String project) {
        return matchInitialProjectAssets(project, null);
    }

    public List<Map<String, Object>> matchInitialProjectAssets(String project, String conversationId) {
        return matchInitialProjectAssets(project, conversationId, true, true, true);
    }

    /** 热配置版本：可关闭首次效果图/优惠海报，也可允许同一会话重复素材。 */
    public List<Map<String, Object>> matchInitialProjectAssets(String project, String conversationId,
                                                               boolean avoidRepeat,
                                                               boolean effectEnabled,
                                                               boolean promoEnabled) {
        if (project == null || project.isBlank()) return List.of();
        LocalDateTime now = LocalDateTime.now();
        Set<Long> sentIds = avoidRepeat ? sentImageIds(conversationId) : Set.of();
        List<Map<String, Object>> candidates = list().stream()
                .filter(this::isEnabled)
                .filter(img -> matchesProject(img, project))
                .filter(img -> isWithinValidity(img, now))
                .filter(img -> !sentIds.contains(imageId(img)))
                .toList();

        List<Map<String, Object>> result = new ArrayList<>(2);
        if (effectEnabled) firstOfType(candidates, "effect").ifPresent(img -> result.add(toChatImage(img)));
        if (promoEnabled) firstOfType(candidates, "promo").ifPresent(img -> result.add(toChatImage(img)));
        return List.copyOf(result);
    }

    /** 应答真实送出后记录图片；调试预览不会调用，避免预览消耗可发送素材。 */
    public void markSent(String conversationId, List<Map<String, Object>> images) {
        if (conversationId == null || conversationId.isBlank() || images == null || images.isEmpty()) return;
        for (Map<String, Object> image : images) {
            long imageId = imageId(image);
            if (imageId <= 0) continue;
            String trigger = "promo".equals(assetTypeOf(image)) ? "promo" : "effect";
            jdbc.update("INSERT INTO conversation_image_send(conversation_id, image_id, trigger_type) " +
                            "VALUES (?, ?, ?) ON CONFLICT (conversation_id, image_id) DO NOTHING",
                    conversationId, imageId, trigger);
        }
    }

    private Set<Long> sentImageIds(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Set.of();
        try {
            return jdbc.queryForList(
                            "SELECT image_id FROM conversation_image_send WHERE conversation_id = ?",
                            Long.class, conversationId)
                    .stream().collect(Collectors.toSet());
        } catch (RuntimeException e) {
            // 升级期间表尚未创建时先保证正常应答；应用重启执行 schema.sql 后自动恢复去重。
            log.warn("读取会话图片发送记录失败: {}", e.getMessage());
            return Set.of();
        }
    }

    private java.util.Optional<Map<String, Object>> firstOfType(List<Map<String, Object>> images, String type) {
        return images.stream().filter(img -> type.equals(assetTypeOf(img))).findFirst();
    }

    private Map<String, Object> toChatImage(Map<String, Object> image) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", image.get("id"));
        result.put("name", image.get("name"));
        result.put("url", image.get("url"));
        result.put("type", assetTypeOf(image));
        return result;
    }

    private boolean matchesProject(Map<String, Object> image, String project) {
        String linked = stringValue(image.get("projects"));
        if (!linked.isBlank()) {
            for (String name : linked.split("[,，、]+")) {
                if (name.trim().equals(project)) return true;
            }
        }
        String configured = stringValue(image.get("project"));
        if (!configured.isBlank()) return configured.equals(project);
        // 兼容升级前仅使用标签的老素材；保存编辑后应优先填写结构化项目。
        return matchesAnyTag(project, stringValue(image.get("tags")));
    }

    /** 图片页仍使用单选项目；保存后同步规范化关联，项目中心则可配置一图多项目。 */
    private void syncProjectLink(long imageId, String project) {
        jdbc.update("DELETE FROM project_asset WHERE image_id=?", imageId);
        if (project == null || project.isBlank()) return;
        List<Long> projectIds = jdbc.queryForList(
                "SELECT p.id FROM service_project p JOIN image_asset i ON i.id=? " +
                        "WHERE p.name=? AND p.status<>'disabled' " +
                        "AND (p.tenant_id IS NULL OR p.tenant_id IS NOT DISTINCT FROM i.tenant_id) " +
                        "AND (p.kf_account_id IS NULL OR p.kf_account_id IS NOT DISTINCT FROM i.kf_account_id) " +
                        "ORDER BY p.tenant_id NULLS LAST,p.kf_account_id NULLS LAST LIMIT 1",
                Long.class, imageId, project.trim());
        if (!projectIds.isEmpty()) {
            jdbc.update("INSERT INTO project_asset(project_id,image_id,asset_role) " +
                            "SELECT ?,i.id,COALESCE(NULLIF(i.asset_type,''),'effect') FROM image_asset i WHERE i.id=?",
                    projectIds.get(0), imageId);
        }
    }

    private boolean matchesAnyTag(String message, String tags) {
        if (message == null || message.isBlank() || tags == null || tags.isBlank()) return false;
        for (String tag : tags.split("[ ,，、]+")) {
            if (!tag.isBlank() && message.contains(tag)) return true;
        }
        return false;
    }

    private boolean containsAny(String text, String... values) {
        if (text == null) return false;
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private boolean isEnabled(Map<String, Object> image) {
        Object value = image.get("enabled");
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean isWithinValidity(Map<String, Object> image, LocalDateTime now) {
        LocalDateTime from = asLocalDateTime(image.get("valid_from"));
        LocalDateTime until = asLocalDateTime(image.get("valid_until"));
        return (from == null || !now.isBefore(from)) && (until == null || !now.isAfter(until));
    }

    private LocalDateTime asLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        }
        return parseDateTime(String.valueOf(value));
    }

    private String assetTypeOf(Map<String, Object> image) {
        String value = stringValue(image.get("asset_type")).toLowerCase(Locale.ROOT);
        if ("promo".equals(value)) return "promo";
        // 兼容尚未经过 schema.sql 回填的旧素材，避免升级重启前后判断不一致。
        String legacyText = stringValue(image.get("name")) + " " + stringValue(image.get("tags"));
        return containsAny(legacyText, "优惠", "活动价", "优惠活动", "海报") ? "promo" : "effect";
    }

    private long imageId(Map<String, Object> image) {
        Object value = image.get("id");
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? -1L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private String normalizeAssetType(String value) {
        String normalized = value == null ? "effect" : value.trim().toLowerCase(Locale.ROOT);
        if (!"effect".equals(normalized) && !"promo".equals(normalized)) {
            throw new IllegalArgumentException("图片类型只能是效果图或优惠海报");
        }
        return normalized;
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("图片有效期格式不正确");
        }
    }

    private void validateValidity(LocalDateTime from, LocalDateTime until) {
        if (from != null && until != null && until.isBefore(from)) {
            throw new IllegalArgumentException("失效时间不能早于生效时间");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

}
