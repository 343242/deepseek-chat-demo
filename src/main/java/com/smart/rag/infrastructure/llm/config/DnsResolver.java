package com.smart.rag.infrastructure.llm.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * DNS 解析抽象 — 预留注入点供未来 DNS rebinding 连接级加固（对抗审查 R5 follow-up）。
 * <p>
 * 默认实现 {@link DefaultDnsResolver} 包装 {@link InetAddress#getAllByName(String)}。
 * 未来注入"resolve→校验 IP→连接同 IP"的 DnsResolver，即可在 HTTP 客户端连接阶段二次校验，
 * 收敛校验时解析公网、连接时解析内网的 TOCTOU 残余风险，无需改 {@code BaseUrlValidator} 签名。
 *
 * @see BaseUrlValidator
 */
public interface DnsResolver {

    /** 解析 host 的所有 A/AAAA 记录；任一返回地址都可能被 {@link BaseUrlValidator} 查内网黑名单 */
    InetAddress[] resolveAll(String host) throws UnknownHostException;
}
