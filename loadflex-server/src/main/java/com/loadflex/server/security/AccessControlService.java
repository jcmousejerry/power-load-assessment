package com.loadflex.server.security;

import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class AccessControlService {

    private static final Set<String> ANALYSIS_ROLES = Set.of("ADMIN", "ANALYST");

    public void requireAnalysisPermission() {
        LoginUser loginUser = UserContext.get();
        if (!ANALYSIS_ROLES.contains(loginUser.getRoleCode())) {
            throw new AccessDeniedException("当前账号只有查看权限，不能修改数据或创建任务");
        }
    }
}
