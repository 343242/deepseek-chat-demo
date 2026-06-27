package com.smart.rag.infrastructure.llm.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * {@link DnsResolver} 默认实现 — 包装 {@link InetAddress#getAllByName(String)}。
 */
@Component
public class DefaultDnsResolver implements DnsResolver {

    @Override
    public InetAddress[] resolveAll(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
