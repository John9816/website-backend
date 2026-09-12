package com.example.website.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Admin admin = new Admin();
    private final Update update = new Update();
    private final Registration registration = new Registration();
    private final BookSources bookSources = new BookSources();
    private final Music music = new Music();
    private final VideoSources videoSources = new VideoSources();
    private final Playlist playlist = new Playlist();

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        /** Access JWT lifetime (minutes). Keep relatively short; clients renew via refresh. */
        private long expireMinutes = 120;
        /** Opaque refresh token lifetime (days). */
        private long refreshExpireDays = 30;
        /** Retain expired and revoked refresh-token rows for audit before cleanup. */
        private long cleanupRetentionDays = 7;
        private String header = "Authorization";
        private String prefix = "Bearer ";
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins;
    }

    @Getter
    @Setter
    public static class Admin {
        private String defaultUsername = "admin";
        private String defaultPassword = "admin123";
    }

    @Getter
    @Setter
    public static class Update {
        private String storageDir = "/opt/website/updates";
        private String baseUrl = "https://api.751152.xyz/updates";
    }

    @Getter
    @Setter
    public static class Registration {
        private final EmailVerification emailVerification = new EmailVerification();
    }

    @Getter
    @Setter
    public static class EmailVerification {
        private boolean required = true;
        private long expireMinutes = 10;
        private long resendIntervalSeconds = 60;
        private int maxAttempts = 5;
        private int ipMaxSends = 10;
        private long ipWindowMinutes = 60;
        private int globalMaxSendsPerMinute = 60;
        private long cleanupRetentionHours = 24;
        private String hmacSecret;
        private final CloudMail cloudMail = new CloudMail();
    }

    @Getter
    @Setter
    public static class CloudMail {
        private String baseUrl = "https://mail.751152.xyz";
        private String username = "admin@751152.xyz";
        private String password;
        private String from = "admin@751152.xyz";
    }

    @Getter
    @Setter
    public static class BookSources {
        private String pinshuzhaiBaseUrl = "https://www.pinshuzhai.com";
        private String yckceoBookCatalog = "https://www.yckceo.com/yuedu/shuyuan/index.html";
        private String yckceoRssCatalog = "https://www.yckceo.com/yuedu/rss/index.html";
        private String legacyBookCatalog = "https://www.lifves.com/Home/book/booksourcelist.html";
        private String legacyBookSourceList = "https://www.lifves.com/api/v2/booksource/list";
    }

    @Getter
    @Setter
    public static class Music {
        private final Chksz chksz = new Chksz();
    }

    @Getter
    @Setter
    public static class Chksz {
        private boolean enabled = true;
        private String baseUrl = "https://api.chksz.com";
        private String apiKey = "";
    }

    @Getter
    @Setter
    public static class VideoSources {
        private String ncat21BaseUrl = "https://www.ncat21.com";
        private int cacheMinutes = 5;
        private int providerConfigCacheMinutes = 30;
        private int providerConfigMaxBytes = 512 * 1024;
        private String defaultSourceId = "ngzmods::剧和短剧";
        private List<WarehouseConfig> warehouses = defaultWarehouses();

        private static List<WarehouseConfig> defaultWarehouses() {
            List<WarehouseConfig> defaults = new ArrayList<>();
            defaults.add(warehouse("ngzmods", "NGZMods",
                    "https://16409.kstore.vip/tv/ngzmods.json", "剧和短剧", 10));
            defaults.add(warehouse("newwex", "NewWex",
                    "https://9280.kstore.vip/newwex.json", "NewDouBan", 20));
            return defaults;
        }

        private static WarehouseConfig warehouse(String id,
                                                 String name,
                                                 String configUrl,
                                                 String defaultSiteKey,
                                                 int sortOrder) {
            WarehouseConfig warehouse = new WarehouseConfig();
            warehouse.setId(id);
            warehouse.setName(name);
            warehouse.setConfigUrl(configUrl);
            warehouse.setKind(com.example.website.dto.video.VideoSourceKind.TVBOX_JSON);
            warehouse.setEnabled(true);
            warehouse.setAllowHttp(false);
            warehouse.setSortOrder(sortOrder);
            warehouse.setDefaultSiteKey(defaultSiteKey);
            return warehouse;
        }

        @Getter
        @Setter
        public static class WarehouseConfig {
            private String id;
            private String name;
            private String configUrl;
            private com.example.website.dto.video.VideoSourceKind kind =
                    com.example.website.dto.video.VideoSourceKind.TVBOX_JSON;
            private boolean enabled = true;
            private boolean allowHttp;
            private int sortOrder;
            private String defaultSiteKey;
        }
    }

    @Getter
    @Setter
    public static class Playlist {
        private final AutoSync autoSync = new AutoSync();
    }

    @Getter
    @Setter
    public static class AutoSync {
        /** 是否启用自动同步（获取歌单详情时） */
        private boolean enabled = true;
        /** 同步间隔（小时），距离上次更新超过此时间才会重新同步 */
        private int intervalHours = 6;
    }
}
