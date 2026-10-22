package com.example.aics.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComKfAdapterTest {

    private static final String TOKEN = "callback-token";
    private static final String CORP_ID = "ww-test-corp";
    private static final String AES_KEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C";

    @Test
    void urlVerificationReturnsDecryptedEcho() {
        WeComTenantService.TenantCredentials tenant = tenant(1, "tenant-a", TOKEN, CORP_ID);
        WeComTenantService tenants = mock(WeComTenantService.class);
        when(tenants.requireEnabledTenant("tenant-a")).thenReturn(tenant);
        WeComCryptoService crypto = new WeComCryptoService(CORP_ID, TOKEN, AES_KEY);
        WeComKfAdapter adapter = new WeComKfAdapter(crypto, mock(WeComKfService.class),
                mock(WeComRuntimeMonitor.class), tenants);
        String encryptedEcho = crypto.encrypt(tenant, "verified-echo");
        String timestamp = "1720000000";
        String nonce = "nonce-1";

        String response = adapter.verifyTenantUrl("tenant-a",
                crypto.sign(tenant, timestamp, nonce, encryptedEcho), timestamp, nonce, encryptedEcho);

        assertThat(response).isEqualTo("verified-echo");
    }

    @Test
    void postCallbackReturnsPlainSuccessAndDispatchesDecryptedXml() {
        WeComTenantService.TenantCredentials tenant = tenant(1, "tenant-a", TOKEN, CORP_ID);
        WeComTenantService tenants = mock(WeComTenantService.class);
        when(tenants.requireEnabledTenant("tenant-a")).thenReturn(tenant);
        WeComCryptoService crypto = new WeComCryptoService(CORP_ID, TOKEN, AES_KEY);
        WeComKfService service = mock(WeComKfService.class);
        WeComRuntimeMonitor monitor = mock(WeComRuntimeMonitor.class);
        WeComKfAdapter adapter = new WeComKfAdapter(
                crypto, service, monitor, tenants);
        String plainXml = "<xml><Event>kf_msg_or_event</Event></xml>";
        String encrypted = crypto.encrypt(tenant, plainXml);
        String timestamp = "1720000000";
        String nonce = "nonce-2";
        String body = "<xml><Encrypt><![CDATA[" + encrypted + "]]></Encrypt></xml>";

        String response = adapter.handleTenantEvent("tenant-a", body,
                crypto.sign(tenant, timestamp, nonce, encrypted), timestamp, nonce);

        assertThat(response).isEqualTo("success");
        verify(service).handleEventAsync(tenant, plainXml);
        verify(monitor).callbackReceived("tenant-a");
        verify(monitor).callbackAccepted("tenant-a");
    }

    @Test
    void invalidSignatureStillAcknowledgesButDoesNotDispatch() {
        WeComTenantService.TenantCredentials tenant = tenant(1, "tenant-a", TOKEN, CORP_ID);
        WeComTenantService tenants = mock(WeComTenantService.class);
        when(tenants.requireEnabledTenant("tenant-a")).thenReturn(tenant);
        WeComCryptoService crypto = new WeComCryptoService(CORP_ID, TOKEN, AES_KEY);
        WeComKfService service = mock(WeComKfService.class);
        WeComKfAdapter adapter = new WeComKfAdapter(
                crypto, service, mock(WeComRuntimeMonitor.class), tenants);
        String encrypted = crypto.encrypt(tenant, "<xml/>");

        String response = adapter.handleTenantEvent("tenant-a",
                "<xml><Encrypt>" + encrypted + "</Encrypt></xml>",
                "invalid-signature", "1720000000", "nonce-3");

        assertThat(response).isEqualTo("success");
        verifyNoInteractions(service);
    }

    @Test
    void callbackSignedByAnotherTenantIsRejected() {
        WeComTenantService.TenantCredentials tenantA = new WeComTenantService.TenantCredentials(
                1, "tenant-a", "A", "corp-a", "secret-a", "token-a", AES_KEY, true);
        WeComTenantService.TenantCredentials tenantB = new WeComTenantService.TenantCredentials(
                2, "tenant-b", "B", "corp-b", "secret-b", "token-b", AES_KEY, true);
        WeComTenantService tenants = mock(WeComTenantService.class);
        when(tenants.requireEnabledTenant("tenant-b")).thenReturn(tenantB);
        WeComKfService service = mock(WeComKfService.class);
        WeComCryptoService crypto = new WeComCryptoService(CORP_ID, TOKEN, AES_KEY);
        WeComKfAdapter adapter = new WeComKfAdapter(
                crypto, service, mock(WeComRuntimeMonitor.class), tenants);
        String encryptedByA = crypto.encrypt(tenantA, "<xml><Event>kf_msg_or_event</Event></xml>");
        String timestamp = "1720000000";
        String nonce = "tenant-isolation";
        String body = "<xml><Encrypt><![CDATA[" + encryptedByA + "]]></Encrypt></xml>";

        String response = adapter.handleTenantEvent("tenant-b", body,
                crypto.sign(tenantA, timestamp, nonce, encryptedByA), timestamp, nonce);

        assertThat(response).isEqualTo("success");
        verifyNoInteractions(service);
    }

    private WeComTenantService.TenantCredentials tenant(long id, String code,
                                                         String token, String corpId) {
        return new WeComTenantService.TenantCredentials(
                id, code, code, corpId, "secret", token, AES_KEY, true);
    }
}
