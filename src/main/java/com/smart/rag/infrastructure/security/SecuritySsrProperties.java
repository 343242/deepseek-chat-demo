package com.smart.rag.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.security.ssrf")
public class SecuritySsrProperties {

    /** 空/null 时 {@link HostSafetyValidator} 退回默认 80/443 */
    private List<Integer> allowedPorts = List.of(80, 443);

    public List<Integer> getAllowedPorts() {
        return allowedPorts;
    }

    public void setAllowedPorts(List<Integer> allowedPorts) {
        this.allowedPorts = allowedPorts;
    }
}
