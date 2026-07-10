package com.example.website.config;

import com.example.website.entity.Category;
import com.example.website.entity.NavLink;
import com.example.website.entity.SysConfig;
import com.example.website.entity.User;
import com.example.website.repository.CategoryRepository;
import com.example.website.repository.NavLinkRepository;
import com.example.website.repository.SysConfigRepository;
import com.example.website.repository.UserRepository;
import com.example.website.service.AiConversationService;
import com.example.website.service.ImageService;
import com.example.website.service.SysConfigService;
import com.example.website.service.music.MusicService;
import com.example.website.service.music.TuneFreeAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final String LEGACY_NAV_MIGRATION_FLAG = "migration.legacyNav.done";

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final NavLinkRepository navLinkRepository;
    private final SysConfigRepository sysConfigRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;
    private final SysConfigService configService;

    @Override
    public void run(String... args) {
        User admin = seedAdmin();
        seedAllConfigs();
        migrateLegacyNavigationData(admin.getId());
    }

    private User seedAdmin() {
        String username = props.getAdmin().getDefaultUsername();
        User existing = userRepository.findByUsername(username).orElse(null);
        if (existing != null) {
            return existing;
        }
        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(props.getAdmin().getDefaultPassword()));
        admin.setRole(User.ROLE_ADMIN);
        User saved = userRepository.save(admin);
        log.warn("Seeded default admin user '{}'. Change password via POST /api/user/change-password.", username);
        return saved;
    }

    private void seedAllConfigs() {
        List<SeedEntry> entries = collectSeedEntries();
        Set<String> existing = sysConfigRepository.findAllByOrderByConfigKeyAsc().stream()
                .map(SysConfig::getConfigKey)
                .collect(Collectors.toSet());

        List<SysConfig> toInsert = new ArrayList<>();
        for (SeedEntry e : entries) {
            if (existing.contains(e.key)) continue;
            SysConfig row = new SysConfig();
            row.setConfigKey(e.key);
            row.setConfigValue(e.value);
            row.setDescription(e.description);
            toInsert.add(row);
        }
        if (!toInsert.isEmpty()) {
            sysConfigRepository.saveAll(toInsert);
            log.info("Seeded {} sys_config rows", toInsert.size());
        }
    }

    private List<SeedEntry> collectSeedEntries() {
        List<SeedEntry> all = new ArrayList<>();
        all.add(new SeedEntry(ImageService.CFG_BASE_URL,
                "https://api.67.si/v1/chat/completions",
                "Upstream image generation API endpoint."));
        all.add(new SeedEntry(ImageService.CFG_API_KEY,
                "sk-xxx",
                "Bearer API key for image generation; replace via admin config API."));
        all.add(new SeedEntry(ImageService.CFG_MODEL,
                "grok-imagine-image-lite",
                "Model name passed to upstream image API."));
        all.add(new SeedEntry(ImageService.CFG_EDIT_BASE_URL,
                "",
                "Optional upstream image edits endpoint. Leave empty to derive /images/edits from image.api.baseUrl."));
        all.add(new SeedEntry(ImageService.CFG_UPLOAD_DIR,
                "uploads/images",
                "Local directory to store generated image files."));
        all.add(new SeedEntry(ImageService.CFG_GOFILE_URL,
                "",
                "go-file server base URL. Leave empty to use local disk."));
        all.add(new SeedEntry(ImageService.CFG_GOFILE_TOKEN,
                "",
                "go-file API token. Leave empty if upload permission is open."));
        all.add(new SeedEntry(ImageService.CFG_REMOTE_URL_MODE,
                "direct",
                "How to persist upstream HTTP image URLs: direct or proxy."));

        all.add(new SeedEntry(AiConversationService.CFG_BASE_URL,
                "https://fufu.iqach.top/v1",
                "Base URL of the upstream AI chat API. /chat/completions is appended automatically."));
        all.add(new SeedEntry(AiConversationService.CFG_API_KEY,
                "",
                "Optional bearer API key for upstream AI chat."));
        all.add(new SeedEntry(AiConversationService.CFG_DEFAULT_MODEL,
                "mimo-v2.5-pro",
                "Default model used for new AI conversations."));
        all.add(new SeedEntry(AiConversationService.CFG_DEFAULT_AUDIO_MODEL,
                "mimo-v2.5-tts",
                "Default TTS model used when a reply requests audio playback."));
        all.add(new SeedEntry(AiConversationService.CFG_MODELS,
                "mimo-v2.5-pro,mimo-v2.5,mimo-v2.5-tts,mimo-v2.5-tts-voicedesign,"
                        + "mimo-v2.5-tts-voiceclone,mimo-v2-pro,mimo-v2-flash,mimo-v2-omni,mimo-v2-tts",
                "Comma-separated list of supported upstream AI models."));

        all.add(new SeedEntry(TuneFreeAuthService.CFG_ACCOUNT, "",
                "TuneFreeNext account."));
        all.add(new SeedEntry(TuneFreeAuthService.CFG_PASSWORD, "",
                "TuneFreeNext password."));
        all.add(new SeedEntry(TuneFreeAuthService.CFG_UDID, "TUNEFREENEXT_BFF_001",
                "Stable device id sent to TuneFree logon."));
        all.add(new SeedEntry(TuneFreeAuthService.CFG_TOKEN, "",
                "Cached TuneFree token, written back after refresh."));
        all.add(new SeedEntry(TuneFreeAuthService.CFG_TOKEN_UPDATED_AT, "",
                "ISO-8601 timestamp of the last successful token refresh."));
        all.add(new SeedEntry(TuneFreeAuthService.CFG_TOKEN_STATUS, "",
                "Status of the last token refresh."));
        all.add(new SeedEntry(MusicService.CFG_PLAY_RESOLVER_ORDER,
                MusicService.DEFAULT_PLAY_RESOLVER_ORDER,
                "Comma-separated music play resolver order: primary, qq_text, cross_source."));
        all.add(new SeedEntry(MusicService.CFG_CROSS_SOURCE_ORDER,
                MusicService.DEFAULT_CROSS_SOURCE_ORDER,
                "Comma-separated cross-source fallback order: qq, netease, kuwo."));

        // Content factory autopilot: unattended topic -> write -> WeChat draft pipeline.
        all.add(new SeedEntry("content.autopilot.enabled", "false",
                "Master switch for the unattended content pipeline. Set true to start auto-producing drafts."));
        all.add(new SeedEntry("content.autopilot.userId", "",
                "User id the autopilot produces articles for. Blank disables the pilot even when enabled."));
        all.add(new SeedEntry("content.autopilot.times", "08:00,12:30,20:00",
                "Comma-separated HH:mm daily slots at which the autopilot produces one article each."));
        all.add(new SeedEntry("content.autopilot.categories", "科技 / 互联网,教育 / 职场,财政金融",
                "Comma-separated columns rotated across the daily slots by slot index."));
        all.add(new SeedEntry("content.autopilot.autoPublish", "false",
                "Attempt WeChat API group-send after drafting. Requires a verified service account with freepublish."));
        all.add(new SeedEntry("content.autopilot.generateCover", "true",
                "Whether the autopilot generates a cover image for each article."));
        all.add(new SeedEntry("content.autopilot.dedupDays", "3",
                "Look-back window (days) of recent titles fed to the topic agent to avoid repeats. 0 disables."));
        return all;
    }

    private void migrateLegacyNavigationData(Long adminUserId) {
        if (configService.getValue(LEGACY_NAV_MIGRATION_FLAG).filter("true"::equals).isPresent()) {
            return;
        }

        List<Category> legacyCategories = categoryRepository.findByUserIdIsNullOrderByIdAsc();
        if (!legacyCategories.isEmpty()) {
            legacyCategories.forEach(category -> category.setUserId(adminUserId));
            categoryRepository.saveAll(legacyCategories);
            log.warn("Migrated {} legacy categories to admin user {}", legacyCategories.size(), adminUserId);
        }

        List<NavLink> legacyLinks = navLinkRepository.findByUserIdIsNullOrderByIdAsc();
        if (!legacyLinks.isEmpty()) {
            java.util.Map<Long, Long> categoryOwners = categoryRepository.findAllById(
                            legacyLinks.stream()
                                    .map(NavLink::getCategoryId)
                                    .collect(Collectors.toSet()))
                    .stream()
                    .filter(category -> category.getUserId() != null)
                    .collect(Collectors.toMap(Category::getId, Category::getUserId));
            legacyLinks.forEach(link -> link.setUserId(
                    categoryOwners.getOrDefault(link.getCategoryId(), adminUserId)
            ));
            navLinkRepository.saveAll(legacyLinks);
            log.warn("Migrated {} legacy links to admin user {}", legacyLinks.size(), adminUserId);
        }

        configService.upsertByKey(LEGACY_NAV_MIGRATION_FLAG, "true",
                "Marks that legacy null-user nav data has been migrated.");
    }

    private static final class SeedEntry {
        final String key;
        final String value;
        final String description;

        SeedEntry(String key, String value, String description) {
            this.key = key;
            this.value = value;
            this.description = description;
        }
    }
}
