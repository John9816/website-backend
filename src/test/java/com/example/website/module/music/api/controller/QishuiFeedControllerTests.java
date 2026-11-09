package com.example.website.module.music.api.controller;

import com.example.website.common.BusinessException;
import com.example.website.common.GlobalExceptionHandler;
import com.example.website.module.identity.application.UserScopeService;
import com.example.website.module.music.api.dto.*;
import com.example.website.module.music.application.QishuiFeedClient;
import com.example.website.module.music.application.QishuiRoamingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QishuiFeedControllerTests {
    private static final String BASE = "/api/v1/music/qishui";
    private final QishuiFeedClient feed = mock(QishuiFeedClient.class);
    private final QishuiRoamingService roaming = mock(QishuiRoamingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new QishuiFeedController(
            feed, roaming, new UserScopeService(null,null)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void everyRouteRequiresWebsiteLoginAndNeverUsesQueryIdentity() throws Exception {
        MockHttpServletRequestBuilder[] requests = {
                get(BASE+"/discovery"), get(BASE+"/roaming/modes"), post(BASE+"/roaming/sessions"),
                post(BASE+"/roaming/sessions/session/next").param("sequence","1"),
                delete(BASE+"/roaming/sessions/session")};
        for (MockHttpServletRequestBuilder request : requests)
            mvc.perform(request.param("userId","999")).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(feed,roaming);
    }

    @Test void routesForwardOnlyAuthenticatedIdentityAndReturnNoStore() throws Exception {
        when(feed.discovery()).thenReturn(new QishuiDiscoveryView(MusicSource.QISHUI,Collections.emptyList()));
        when(roaming.modes(7L)).thenReturn(Collections.singletonList(
                new QishuiFeedModeView("recommended","为你推荐","推荐",null,null)));
        QishuiRoamingPageView page = new QishuiRoamingPageView("session",MusicSource.QISHUI,"2",0,1,true,
                Instant.parse("2030-01-01T00:30:00Z"),Collections.emptyList());
        when(roaming.start(7L,"2")).thenReturn(page);
        when(roaming.next(7L,"session",1,null)).thenReturn(page);
        mvc.perform(get(BASE+"/discovery").requestAttr("userId",7L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("qishui"))
                .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get(BASE+"/roaming/modes").requestAttr("userId",7L).param("userId","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("recommended"));
        mvc.perform(post(BASE+"/roaming/sessions").requestAttr("userId",7L).param("userId","999").param("modeId","2"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.nextSequence").value(1)).andExpect(jsonPath("$.data.userId").doesNotExist());
        mvc.perform(post(BASE+"/roaming/sessions/session/next").requestAttr("userId",7L).param("sequence","1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(delete(BASE+"/roaming/sessions/session").requestAttr("userId",7L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(roaming).modes(7L); verify(roaming).start(7L,"2");
        verify(roaming).next(7L,"session",1,null); verify(roaming).close(7L,"session");
    }

    @Test void invalidSequenceIsAClientErrorAndFailuresAreNotCacheable() throws Exception {
        for (String sequence : new String[]{null,"abc","9223372036854775808"}) {
            MockHttpServletRequestBuilder request = post(BASE+"/roaming/sessions/session/next").requestAttr("userId",7L);
            if (sequence != null) request.param("sequence",sequence);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(header().string("Cache-Control","no-store"));
        }
        verifyNoInteractions(roaming);
        when(roaming.start(7L,null)).thenThrow(new BusinessException(409,"Bind a Qishui account before roaming"));
        mvc.perform(post(BASE+"/roaming/sessions").requestAttr("userId",7L))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(409))
                .andExpect(header().string("Cache-Control","no-store"));
    }
}
