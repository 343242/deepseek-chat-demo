package com.smart.rag.infrastructure.security;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 通用 host 安全校验器（SSRF 防护）。
 * <p>
 * <b>威胁模型</b>：调用方（MCP server URL / 任意 user-supplied URL）让用户提交任意 URL，
 * 服务端用该 URL 发请求。恶意用户可填内网地址（云 metadata {@code 169.254.169.254}、Redis、内网服务）
 * → SSRF 探测/攻击内网、窃取云 IAM 凭证。本组件在配置入库前 fail-fast（不落库、不发请求）。
 * <p>
 * <b>防护层</b>：
 * <ol>
 *   <li>协议白名单：仅 http/https（拒 file/gopher/ftp/dict 等）</li>
 *   <li>端口白名单：默认 80/443（{@code app.security.ssrf.allowed-ports} 可配）</li>
 *   <li>形态/编码绕过：URL 解码归一化 → 拒 localhost / {@code *.local} / {@code *.internal} / 末尾点</li>
 *   <li>内网 IP 黑名单：DNS 解析 host <b>所有</b> A/AAAA 记录，任一命中即拒
 *       （含云 metadata、CGN、IPv4-mapped IPv6）</li>
 * </ol>
 * 重定向兜底（{@code followRedirects=false}）在 HTTP 客户端构建层，不在本组件。
 * <p>
 * 非法 URL 抛 {@link IllegalArgumentException}，由 ControllerAdvice 映射 HTTP 400。
 * <p>
 * <b>不适用面</b>：{@link #validate()} 不适用于 LLM provider URL——其内网/回环一律拒绝
 * 与端口白名单会误杀回环同机部署（Ollama）与内网网关；LLM 免 key 豁免走
 * {@link #isLoopbackEndpoint(String)} 字面回环判定。
 *
 * @see DnsResolver
 */
@Component
public class HostSafetyValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<Integer> DEFAULT_PORTS = Set.of(80, 443);

    private final SecuritySsrProperties ssrProperties;
    private final DnsResolver dnsResolver;

    public HostSafetyValidator(SecuritySsrProperties ssrProperties, DnsResolver dnsResolver) {
        this.ssrProperties = ssrProperties;
        this.dnsResolver = dnsResolver;
    }

    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        URI uri = parse(normalize(url));

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("url 协议必须为 http/https，拒绝: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url 缺少 host");
        }

        int port = uri.getPort();
        if (port != -1 && !resolveAllowedPorts().contains(port)) {
            throw new IllegalArgumentException("url 端口不在白名单，拒绝: " + port);
        }

        String hostLower = host.toLowerCase();
        if ("localhost".equals(hostLower)
                || hostLower.endsWith(".local")
                || hostLower.endsWith(".internal")
                || hostLower.endsWith(".")) {
            throw new IllegalArgumentException("url host 禁止 localhost/.local/.internal/末尾点: " + host);
        }

        InetAddress[] addrs;
        try {
            addrs = dnsResolver.resolveAll(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("url host 无法解析: " + host, e);
        }
        for (InetAddress addr : addrs) {
            if (isInternalAddress(addr)) {
                throw new IllegalArgumentException(
                    "url host 解析到内网地址 " + addr.getHostAddress() + "，拒绝（SSRF 防护）");
            }
        }
    }

    /** URL 解码归一化（最多两层，防双重编码绕过如 {@code %2531} → {@code %31} → {@code 1}） */
    private static String normalize(String url) {
        String current = url;
        for (int i = 0; i < 2; i++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(current, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return current;
            }
            if (decoded.equals(current)) {
                return current;
            }
            current = decoded;
        }
        return current;
    }

    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url 格式非法: " + e.getMessage(), e);
        }
    }

    private Set<Integer> resolveAllowedPorts() {
        var configured = ssrProperties.getAllowedPorts();
        return (configured == null || configured.isEmpty()) ? DEFAULT_PORTS : Set.copyOf(configured);
    }

    // ===== 内网 IP 黑名单 =====

    /**
     * host 字面回环判定（免 key 豁免用，design llm-client-stateless §1 决策 4）：
     * localhost（忽略大小写）/ 127.0.0.0/8 IPv4 字面 / {@code [::1]} 字面。
     * <p>
     * 纯字面解析、<b>不发 DNS</b>；解析失败或 host 缺失 → false（fail-safe）。
     * 供 {@code ProviderConfig.isAvailable()} 的免 key 豁免门卫使用（yml 单源、无输入面，
     * 运维直接写回环字面量即可；自定义 hosts 别名不在豁免面）。
     * <p>
     * 注意 {@link URI#getHost()} 对 IPv6 字面量返回带方括号形式（如 {@code [::1]}）。
     * 静态方法：调用方（yml 绑定 record）无法注入 bean，与本类实例方法语义无关。
     */
    public static boolean isLoopbackEndpoint(String url) {
        if (url == null || url.isBlank()) return false;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) return false;
        String hostLower = host.toLowerCase();
        if ("localhost".equals(hostLower)) return true;
        if (hostLower.startsWith("[") && hostLower.endsWith("]")) {
            return "[::1]".equals(hostLower);
        }
        return isIpv4LoopbackLiteral(host);
    }

    /** 127.0.0.0/8 IPv4 字面判定（点分四段、各段 0-255、首段 127） */
    private static boolean isIpv4LoopbackLiteral(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (char c : part.toCharArray()) {
                if (c < '0' || c > '9') return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return Integer.parseInt(parts[0]) == 127;
    }

    static boolean isInternalAddress(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return isInternalIpv4(b);
        }
        if (b.length == 16) {
            if (isIpv4Mapped(b)) {
                return isInternalIpv4(extractIpv4(b));
            }
            return isInternalIpv6(b);
        }
        return false;
    }

    private static boolean isInternalIpv4(byte[] b) {
        int ip = ipv4ToInt(b);
        return inIpv4Range(ip, 0x00000000, 8)   // 0.0.0.0/8
            || inIpv4Range(ip, 0x0A000000, 8)   // 10.0.0.0/8
            || inIpv4Range(ip, 0x7F000000, 8)   // 127.0.0.0/8（loopback）
            || inIpv4Range(ip, 0xA9FE0000, 16)  // 169.254.0.0/16（含云 metadata 169.254.169.254）
            || inIpv4Range(ip, 0xAC100000, 12)  // 172.16.0.0/12
            || inIpv4Range(ip, 0xC0A80000, 16)  // 192.168.0.0/16
            || inIpv4Range(ip, 0x64400000, 10); // 100.64.0.0/10（CGN）
    }

    private static boolean isInternalIpv6(byte[] b) {
        if (isAllZero(b, 0, 15) && b[15] == 1) return true;   // ::1 loopback
        if (isAllZero(b, 0, 16)) return true;                 // :: any-local
        if ((b[0] & 0xFE) == 0xFC) return true;               // fc00::/7 ULA
        return b[0] == (byte) 0xFE && (b[1] & 0xC0) == 0x80; // fe80::/10 link-local
    }

    /** IPv4-mapped IPv6：::ffff:w.x.y.z（前 10 byte 为 0，byte[10..11]=0xFF，后 4 byte 为 IPv4） */
    private static boolean isIpv4Mapped(byte[] b) {
        if (b[10] != (byte) 0xFF || b[11] != (byte) 0xFF) return false;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private static byte[] extractIpv4(byte[] b) {
        return new byte[]{b[12], b[13], b[14], b[15]};
    }

    private static int ipv4ToInt(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static boolean inIpv4Range(int ip, int network, int prefix) {
        int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
        return (ip & mask) == (network & mask);
    }

    private static boolean isAllZero(byte[] b, int start, int endExclusive) {
        for (int i = start; i < endExclusive; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }
}
