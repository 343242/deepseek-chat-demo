package com.smart.rag.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HostSafetyValidator 单元测试 — SSRF 防护全覆盖。
 * <p>
 * DnsResolver 用 Mockito mock（避免真实联网）；IP 字面量经 {@link InetAddress#getByName(String)}
 * 解析不触发 DNS，安全用于测试构造。十进制 IP 用例走真实 {@link DefaultDnsResolver}（字面量不联网）。
 */
class HostSafetyValidatorTest {

    private SecuritySsrProperties props;
    private DnsResolver dnsResolver;
    private HostSafetyValidator validator;

    @BeforeEach
    void setUp() {
        props = new SecuritySsrProperties();
        props.setAllowedPorts(List.of(80, 443));
        dnsResolver = mock(DnsResolver.class);
        validator = new HostSafetyValidator(props, dnsResolver);
    }

    private void dnsReturns(InetAddress... addrs) throws UnknownHostException {
        when(dnsResolver.resolveAll(anyString())).thenReturn(addrs);
    }

    private static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    // ===== AC25 协议白名单 =====

    @Test
    void http_and_https_public_host_pass() throws Exception {
        dnsReturns(addr("8.8.8.8"));
        assertThatCode(() -> validator.validate("http://api.example.com")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("https://api.example.com/v1/chat")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "gopher://127.0.0.1:6379/_INFO", "ftp://x.com", "dict://x.com"})
    void non_http_schemes_rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("协议");
    }

    // ===== AC27 端口白名单 =====

    @Test
    void whitelisted_or_omitted_port_passes() throws Exception {
        dnsReturns(addr("8.8.8.8"));
        assertThatCode(() -> validator.validate("http://api.example.com:80")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("https://api.example.com:443")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("http://api.example.com")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {22, 25, 3306, 5432, 6379, 8080, 9200, 11211, 27017})
    void non_whitelisted_port_rejected(int port) {
        assertThatThrownBy(() -> validator.validate("http://api.example.com:" + port))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("端口");
    }

    @Test
    void custom_allowed_ports_from_config() throws Exception {
        props.setAllowedPorts(List.of(11434));
        dnsReturns(addr("8.8.8.8"));
        assertThatCode(() -> validator.validate("http://api.example.com:11434")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate("http://api.example.com:443"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== AC28 形态/编码绕过 =====

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost", "http://localhost:80", "http://foo.local",
        "http://api.internal", "http://evil.com."})
    void localhost_local_internal_trailingDot_rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void url_encoded_internal_ip_decoded_before_check() {
        // %31%32%37%2e%30%2e%30%2e%31 → 127.0.0.1；走真实 DnsResolver（字面量不联网）
        HostSafetyValidator realDns = new HostSafetyValidator(props, new DefaultDnsResolver());
        assertThatThrownBy(() -> realDns.validate("http://%31%32%37%2e%30%2e%30%2e%31"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("内网");
    }

    // ===== AC26 内网 IPv4 黑名单 =====

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.1.2.3", "10.0.0.1", "10.255.255.255",
        "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.168.1.1",
        "100.64.0.1", "100.127.255.255", "0.0.0.0"})
    void internal_ipv4_rejected(String ip) throws Exception {
        dnsReturns(addr(ip));
        assertThatThrownBy(() -> validator.validate("http://example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("内网");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11.0.0.1", "172.32.0.1", "100.128.0.1", "128.0.0.1"})
    void public_adjacent_ipv4_pass(String ip) throws Exception {
        // 边界外（11/8、172.32、100.128、128）非内网段
        dnsReturns(addr(ip));
        assertThatCode(() -> validator.validate("http://example.com")).doesNotThrowAnyException();
    }

    // ===== AC26 内网 IPv6 + IPv4-mapped =====

    @Test
    void ipv6_loopback_ula_linklocal_rejected() throws Exception {
        dnsReturns(addr("::1"));
        assertThatThrownBy(() -> validator.validate("http://example.com")).hasMessageContaining("内网");

        dnsReturns(addr("fc00::1"));
        assertThatThrownBy(() -> validator.validate("http://example.com")).hasMessageContaining("内网");

        dnsReturns(addr("fe80::1"));
        assertThatThrownBy(() -> validator.validate("http://example.com")).hasMessageContaining("内网");
    }

    @Test
    void ipv4_mapped_ipv6_rejected() throws Exception {
        // ::ffff:127.0.0.1 — IPv6 包装绕过
        dnsReturns(addr("::ffff:127.0.0.1"));
        assertThatThrownBy(() -> validator.validate("http://example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("内网");
    }

    @Test
    void public_ipv6_passes() throws Exception {
        dnsReturns(addr("2001:4860:4860::8888")); // Google public DNS
        assertThatCode(() -> validator.validate("http://example.com")).doesNotThrowAnyException();
    }

    // ===== 十进制 IP（走真实 DefaultDnsResolver，字面量不联网）=====

    @Test
    void decimal_ip_resolved_to_internal_and_rejected() {
        HostSafetyValidator realDns = new HostSafetyValidator(props, new DefaultDnsResolver());
        // 2130706433 = 127.0.0.1；InetAddress.getAllByName 能解析十进制 IP 字面量
        assertThatThrownBy(() -> realDns.validate("http://2130706433"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("内网");
    }

    // ===== 多 A 记录任一内网即拒 =====

    @Test
    void any_internal_among_multiple_records_rejected() throws Exception {
        dnsReturns(addr("8.8.8.8"), addr("10.0.0.1"));
        assertThatThrownBy(() -> validator.validate("http://example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("内网");
    }

    // ===== DNS 失败 / 空值 =====

    @Test
    void dns_unknown_host_rejected() throws Exception {
        when(dnsResolver.resolveAll(anyString())).thenThrow(new UnknownHostException("nx"));
        assertThatThrownBy(() -> validator.validate("http://example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无法解析");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blank_rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("空");
    }

    @Test
    void null_rejected() {
        assertThatThrownBy(() -> validator.validate(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("空");
    }
}
