package com.example.website;

import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.entity.MembershipType;
import com.example.website.entity.User;
import com.example.website.entity.UserMembership;
import com.example.website.repository.MusicPlayHistoryRepository;
import com.example.website.repository.UserMembershipRepository;
import com.example.website.repository.UserRepository;
import com.example.website.service.music.MusicService;
import com.example.website.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MembershipFreeLifetimeIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private UserMembershipRepository memberships;
    @Autowired private MusicPlayHistoryRepository history;
    @Autowired private JwtUtil jwt;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private MusicService musicService;

    @BeforeEach
    void providePlayableSong() {
        PlayInfo song = new PlayInfo();
        song.setId("free-song");
        song.setSource(MusicSource.QQ);
        song.setName("Free listening");
        song.setArtist("Test artist");
        song.setPlayUrl("https://audio.example.com/free-song.mp3");
        when(musicService.play("qq", "free-song", null)).thenReturn(song);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/music/play", "/music/play"})
    void nonMemberCanPrefetchAndPlayWithoutRedeeming(String path) throws Exception {
        User user = createUser(User.ROLE_USER);
        String token = token(user);
        mockMvc.perform(get(path).header("Authorization", token)
                        .param("source", "qq").param("id", "free-song").param("prefetch", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playUrl").value("https://audio.example.com/free-song.mp3"));
        assertEquals(0, history.countByUserId(user.getId()));
        mockMvc.perform(get(path).header("Authorization", token)
                        .param("source", "qq").param("id", "free-song"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("free-song"));
        assertEquals(1, history.countByUserId(user.getId()));
        assertFalse(memberships.findByUserId(user.getId()).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/music/play", "/music/play"})
    void expiredMembershipDoesNotBlockPlaybackOrGetExtended(String path) throws Exception {
        User user = createUser(User.ROLE_USER);
        LocalDateTime expiry = LocalDateTime.now().minusDays(2).withNano(0);
        UserMembership membership = new UserMembership();
        membership.setUserId(user.getId());
        membership.setMembershipType(MembershipType.DAY);
        membership.setExpiresAt(expiry);
        memberships.saveAndFlush(membership);
        mockMvc.perform(get(path).header("Authorization", token(user))
                        .param("source", "qq").param("id", "free-song"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playUrl").isNotEmpty());
        assertEquals(expiry, memberships.findByUserId(user.getId()).get().getExpiresAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/music/play", "/music/play"})
    void playbackStillRequiresLogin(String path) throws Exception {
        mockMvc.perform(get(path).param("source", "qq").param("id", "free-song"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registeredAccountHasLifetimeMembershipWithoutRedeeming() throws Exception {
        String token = token(createUser(User.ROLE_USER));
        mockMvc.perform(get("/api/user/membership").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("LIFETIME"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.lifetime").value(true))
                .andExpect(jsonPath("$.data.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.data.remainingSeconds").doesNotExist());
        mockMvc.perform(post("/api/user/membership/redeem").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"OW-TEST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void newlyRegisteredUserCanImmediatelyPlayWithLifetimeMembership() throws Exception {
        String username = "new_free_" + UUID.randomUUID().toString().substring(0, 8);
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@751152.xyz\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = "Bearer " + objectMapper.readTree(response).path("data").path("token").asText();
        mockMvc.perform(get("/api/user/membership").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("LIFETIME"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.lifetime").value(true));
        mockMvc.perform(get("/api/v1/music/play").header("Authorization", token)
                        .param("source", "qq").param("id", "free-song"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playUrl").value("https://audio.example.com/free-song.mp3"));
    }

    private User createUser(String role) {
        User user = new User();
        user.setUsername("free_" + UUID.randomUUID().toString().substring(0, 8));
        user.setEmail(user.getUsername() + "@751152.xyz");
        user.setPassword("unused-test-password");
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private String token(User user) {
        return "Bearer " + jwt.generateToken(user.getId(), user.getUsername(), user.getRole());
    }
}
