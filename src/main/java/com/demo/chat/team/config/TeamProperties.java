package com.demo.chat.team.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 团队功能配置
 */
@Component
@ConfigurationProperties(prefix = "app.team")
public class TeamProperties {

    /** 审批超时天数 */
    private int approvalTimeoutDays = 7;

    /** 新建团队时创建者的默认上传额度（MB） */
    private long defaultCreatorUploadLimitMb = 200;

    /** 新成员加入时的默认上传额度（MB） */
    private long defaultMemberUploadLimitMb = 50;

    /** 单个团队最大成员数 */
    private int maxMembersPerTeam = 50;

    /** 单个用户最大加入团队数 */
    private int maxTeamsPerUser = 10;

    public int getApprovalTimeoutDays() { return approvalTimeoutDays; }
    public void setApprovalTimeoutDays(int approvalTimeoutDays) { this.approvalTimeoutDays = approvalTimeoutDays; }
    public long getDefaultCreatorUploadLimitMb() { return defaultCreatorUploadLimitMb; }
    public void setDefaultCreatorUploadLimitMb(long defaultCreatorUploadLimitMb) { this.defaultCreatorUploadLimitMb = defaultCreatorUploadLimitMb; }
    public long getDefaultMemberUploadLimitMb() { return defaultMemberUploadLimitMb; }
    public void setDefaultMemberUploadLimitMb(long defaultMemberUploadLimitMb) { this.defaultMemberUploadLimitMb = defaultMemberUploadLimitMb; }
    public int getMaxMembersPerTeam() { return maxMembersPerTeam; }
    public void setMaxMembersPerTeam(int maxMembersPerTeam) { this.maxMembersPerTeam = maxMembersPerTeam; }
    public int getMaxTeamsPerUser() { return maxTeamsPerUser; }
    public void setMaxTeamsPerUser(int maxTeamsPerUser) { this.maxTeamsPerUser = maxTeamsPerUser; }
}
