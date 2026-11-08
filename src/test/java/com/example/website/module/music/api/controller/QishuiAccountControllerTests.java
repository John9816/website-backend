package com.example.website.module.music.api.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.website.common.BusinessException;
import com.example.website.module.music.api.dto.QishuiAccountView;
import com.example.website.module.identity.application.UserScopeService;
import com.example.website.module.music.application.QishuiAccountService;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class QishuiAccountControllerTests {
    @Test
    void requiresWebsiteIdentityAndIgnoresCallerSuppliedUserId() {
        QishuiAccountService service = mock(QishuiAccountService.class);
        QishuiAccountController controller =
                new QishuiAccountController(service, new UserScopeService(null, null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("userId", "999");
        assertThrows(BusinessException.class, () -> controller.start(request));
        verifyNoInteractions(service);
        request.setAttribute("userId", 7L);
        when(service.start(7L)).thenReturn(new QishuiAccountView());
        assertEquals("no-store", controller.start(request).getHeaders().getCacheControl());
        verify(service).start(7L);
        controller.poll(request, "session");
        verify(service).poll(7L, "session");
        controller.account(request);
        verify(service).account(7L);
        controller.refresh(request);
        verify(service).refresh(7L);
        controller.delete(request);
        verify(service).delete(7L);
    }
}
