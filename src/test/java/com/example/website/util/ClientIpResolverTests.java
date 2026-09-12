package com.example.website.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTests {

    @Test
    void trustedReverseProxyAddressWinsOverForwardedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "198.51.100.10");
        request.addHeader("CF-Connecting-IP", "203.0.113.20");
        request.addHeader("X-Forwarded-For", "203.0.113.30, 127.0.0.1");

        assertEquals("198.51.100.10", ClientIpResolver.resolve(request));
    }

    @Test
    void fallsBackToFirstForwardedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.11, 127.0.0.1");

        assertEquals("198.51.100.11", ClientIpResolver.resolve(request));
    }

    @Test
    void ignoresForwardedHeadersFromDirectClients() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.50");
        request.addHeader("X-Real-IP", "203.0.113.10");
        request.addHeader("CF-Connecting-IP", "203.0.113.11");

        assertEquals("198.51.100.50", ClientIpResolver.resolve(request));
    }
}
