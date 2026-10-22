package com.example.aics.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SecurityHeadersFilterTest {

    @Test
    void allowsCurrentVueRuntimeCompilerAndKeepsCoreProtections() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String policy = response.getHeader("Content-Security-Policy");
        assertTrue(policy.contains("script-src 'self' 'unsafe-inline' 'unsafe-eval'"));
        assertTrue(policy.contains("object-src 'none'"));
        assertTrue(policy.contains("frame-ancestors 'none'"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        verify(chain).doFilter(request, response);
    }
}
