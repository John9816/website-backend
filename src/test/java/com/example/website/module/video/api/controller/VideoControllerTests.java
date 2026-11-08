package com.example.website.module.video.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.video.api.dto.VideoCategoryView;
import com.example.website.module.video.api.dto.VideoDetailView;
import com.example.website.module.video.api.dto.VideoFilterView;
import com.example.website.module.video.api.dto.VideoPlayView;
import com.example.website.module.video.api.dto.VideoSearchPageView;
import com.example.website.module.membership.application.MembershipService;
import com.example.website.module.video.application.NcatVideoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class VideoControllerTests {

    @Mock
    private NcatVideoService service;

    @Mock
    private MembershipService membershipService;

    @Mock
    private HttpServletRequest request;

    @Test
    void exposesCatalogSearchAndFiltersThroughStableResponses() {
        VideoController controller = new VideoController(service, membershipService);
        when(service.categories()).thenReturn(Collections.singletonList(
                new VideoCategoryView("1", "电影", "/channel/1.html")));
        when(service.filters()).thenReturn(new VideoFilterView(
                Collections.singletonList("2026"),
                Collections.singletonList("中国大陆"),
                Collections.singletonList("悬疑"),
                Collections.singletonList("最新")));

        ApiResponse<?> categories = controller.categories();
        ApiResponse<?> filters = controller.filters();

        assertEquals(0, categories.getCode());
        assertEquals(1, ((java.util.List<?>) categories.getData()).size());
        assertEquals(0, filters.getCode());
        verify(service).categories();
        verify(service).filters();
    }

    @Test
    void playRequiresMembershipBeforeResolvingDirectMedia() {
        VideoController controller = new VideoController(service, membershipService);
        when(request.getAttribute("userId")).thenReturn(9L);
        when(service.play("42", "1", "2")).thenReturn(new VideoPlayView(
                "42", "1", "2", "https://cdn.ncat21.com/42.m3u8", "hls",
                null, "https://www.ncat21.com/play/42-1-2.html", true, "ok"));

        ApiResponse<VideoPlayView> response = controller.play(request, "42", "1", "2");

        assertEquals(0, response.getCode());
        assertEquals("hls", response.getData().getFormat());
        verify(membershipService).requireActiveMembership(9L);
        verify(service).play("42", "1", "2");
    }

    @Test
    void forwardsSearchAndDetailParametersWithoutMutatingThem() {
        VideoController controller = new VideoController(service, membershipService);
        when(service.search("消失的人", "1", 2, 30))
                .thenReturn(new VideoSearchPageView(Collections.emptyList(), 2, 30, 0, false));
        when(service.detail("333692")).thenReturn(new VideoDetailView(
                "333692", "消失的人", null, "", Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                "https://www.ncat21.com/detail/333692.html"));

        assertEquals(0, controller.search("消失的人", "1", 2, 30).getCode());
        assertEquals("333692", controller.detail("333692").getData().getId());
        verify(service).search("消失的人", "1", 2, 30);
        verify(service).detail("333692");
    }

    @Test
    void exposesDetailThroughRestStyleCompatibilityRoute() throws Exception {
        VideoController controller = new VideoController(service, membershipService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(service.detail("333692")).thenReturn(new VideoDetailView(
                "333692", "消失的人", null, "", Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                "https://www.ncat21.com/detail/333692.html"));

        mockMvc.perform(get("/api/v1/videos/{id}/detail", "333692"))
                .andExpect(status().isOk());

        verify(service).detail("333692");
    }

    @Test
    void exposesPlayThroughRestStyleCompatibilityRoute() throws Exception {
        VideoController controller = new VideoController(service, membershipService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(service.play("42", "1", "2")).thenReturn(new VideoPlayView(
                "42", "1", "2", "https://cdn.ncat21.com/42.m3u8", "hls",
                null, "https://www.ncat21.com/play/42-1-2.html", true, "ok"));

        mockMvc.perform(get("/api/v1/videos/{id}/play", "42")
                        .requestAttr("userId", 9L)
                        .param("episode", "1")
                        .param("line", "2"))
                .andExpect(status().isOk());

        verify(membershipService).requireActiveMembership(9L);
        verify(service).play("42", "1", "2");
    }
}
