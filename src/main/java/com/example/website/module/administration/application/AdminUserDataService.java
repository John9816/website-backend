package com.example.website.module.administration.application;



import com.example.website.module.identity.api.dto.AdminUserDetailView;
import com.example.website.module.ai.infrastructure.persistence.AiConversationRepository;
import com.example.website.module.content.infrastructure.persistence.ContentArticleRepository;
import com.example.website.module.images.infrastructure.persistence.GeneratedImageRepository;
import com.example.website.module.knowledge.infrastructure.persistence.KbDocRepository;
import com.example.website.module.music.infrastructure.persistence.MusicFavoriteRepository;
import com.example.website.module.music.infrastructure.persistence.MusicPlayHistoryRepository;
import com.example.website.module.music.infrastructure.persistence.UserPlaylistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

/** Explicit cross-module administrative aggregation; account guards remain in AdminUserService. */
@Service
@RequiredArgsConstructor
class AdminUserDataService {
    private final GeneratedImageRepository generatedImageRepository;
    private final ContentArticleRepository contentArticleRepository;
    private final KbDocRepository kbDocRepository;
    private final AiConversationRepository aiConversationRepository;
    private final MusicFavoriteRepository musicFavoriteRepository;
    private final UserPlaylistRepository userPlaylistRepository;
    private final MusicPlayHistoryRepository musicPlayHistoryRepository;
    @PersistenceContext
    private EntityManager entityManager;

    void populateCounts(Long userId, AdminUserDetailView view) {
        view.setImageCount(generatedImageRepository.countByUserId(userId));
        view.setArticleCount(contentArticleRepository.countByUserId(userId));
        view.setKbDocCount(kbDocRepository.countByUserId(userId));
        view.setConversationCount(aiConversationRepository.countByUserId(userId));
        view.setMusicFavoriteCount(musicFavoriteRepository.countByUserId(userId));
        view.setPlaylistCount(userPlaylistRepository.countByUserId(userId));
        view.setPlayHistoryCount(musicPlayHistoryRepository.countByUserId(userId));
    }

    void purgeUserOwnedData(Long userId) {
        // Nested dependencies first (no DB FKs to users, but child rows reference parents).
        entityManager.createNativeQuery(
                        "DELETE FROM user_playlist_item WHERE playlist_id IN (SELECT id FROM user_playlist WHERE user_id = ?1)")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM user_playlist WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();

        entityManager.createNativeQuery(
                        "DELETE FROM ai_chat_message WHERE conversation_id IN (SELECT id FROM ai_conversation WHERE user_id = ?1)")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM ai_conversation WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();

        entityManager.createNativeQuery(
                        "DELETE FROM kb_doc_tag WHERE doc_id IN (SELECT id FROM kb_doc WHERE user_id = ?1)")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "DELETE FROM kb_doc_version WHERE doc_id IN (SELECT id FROM kb_doc WHERE user_id = ?1)")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "DELETE FROM kb_doc_share WHERE user_id = ?1 OR doc_id IN (SELECT id FROM kb_doc WHERE user_id = ?1)")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM kb_doc WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM kb_tag WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM kb_space WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();

        entityManager.createNativeQuery("DELETE FROM music_favorite WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM music_play_history WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM music_share WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();

        entityManager.createNativeQuery("DELETE FROM membership_redemption WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM user_membership WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("UPDATE redeem_code SET created_by_user_id = NULL WHERE created_by_user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("UPDATE redeem_code SET redeemed_by_user_id = NULL WHERE redeemed_by_user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();

        entityManager.createNativeQuery("DELETE FROM generated_image WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM image_generation_task WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM content_article WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();

        entityManager.createNativeQuery("DELETE FROM nav_link WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM category WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        entityManager.createNativeQuery("DELETE FROM auth_refresh_tokens WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
    }

}
