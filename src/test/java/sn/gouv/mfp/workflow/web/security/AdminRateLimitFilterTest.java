package sn.gouv.mfp.workflow.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Limitation de débit : quota par client, 429 au-delà, autres chemins ignorés. */
class AdminRateLimitFilterTest {

    private final AdminRateLimitFilter filter = new AdminRateLimitFilter();
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void underQuota_shouldPassThrough() throws Exception {
        MockHttpServletRequest request = adminRequest("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void overQuota_shouldReturn429WithoutCallingChain() throws Exception {
        MockHttpServletResponse last = null;
        for (long i = 0; i < AdminRateLimitFilter.MAX_REQUESTS_PER_WINDOW + 1; i++) {
            last = new MockHttpServletResponse();
            filter.doFilter(adminRequest("10.0.0.2"), last, chain);
        }

        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getContentAsString()).contains("RateLimitExceeded");
        verify(chain, times((int) AdminRateLimitFilter.MAX_REQUESTS_PER_WINDOW))
                .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void quotas_shouldBeIndependentPerClient() throws Exception {
        for (long i = 0; i < AdminRateLimitFilter.MAX_REQUESTS_PER_WINDOW; i++) {
            filter.doFilter(adminRequest("10.0.0.3"), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(adminRequest("10.0.0.4"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void nonAdminPath_shouldBeSkipped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances/1");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(request, response);
    }

    private MockHttpServletRequest adminRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/workflows/import");
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
