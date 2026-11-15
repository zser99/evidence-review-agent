package com.sora.evidence.auth;

import com.sora.evidence.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 토큰의 사용자를 DB에서 다시 읽어 역할·소유권을 서버에서 검증한다. */
@Component
public class CurrentUser {
    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public User get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return users.findByUsername(a.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다. 다시 로그인해 주세요."));
    }
}
