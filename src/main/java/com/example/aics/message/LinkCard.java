package com.example.aics.message;

/**
 * 渠道中立的图文链接卡片。
 *
 * 网页端直接渲染；企业微信端转换为 kf/send_msg 的 link 消息。
 */
public record LinkCard(
        String title,
        String description,
        String url,
        String thumbnailUrl
) {
    public LinkCard {
        title = clean(title);
        description = clean(description);
        url = clean(url);
        thumbnailUrl = clean(thumbnailUrl);
    }

    /** 企微缩略图必须是公网绝对地址；网页端仍可使用相对路径。 */
    public LinkCard withPublicBaseUrl(String publicBaseUrl) {
        if (thumbnailUrl.isBlank() || isHttpUrl(thumbnailUrl)) return this;
        String base = clean(publicBaseUrl);
        if (base.isBlank()) return this;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String path = thumbnailUrl.startsWith("/") ? thumbnailUrl : "/" + thumbnailUrl;
        return new LinkCard(title, description, url, base + path);
    }

    /** link 消息失败时退回普通文本，确保客户仍能打开地址。 */
    public String fallbackText() {
        if (url.isBlank()) return title;
        return (title.isBlank() ? "查看腾讯地图地址" : title + "（腾讯地图）") + "：" + url;
    }

    public boolean isDeliverable() {
        return !title.isBlank() && isHttpUrl(url);
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
