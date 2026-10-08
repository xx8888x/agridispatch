package com.agridispatch.security;

import com.agridispatch.repository.UserRepository;
import com.agridispatch.repository.WorkerRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Token 撤銷檢查（規格 03 第 2、5 節）：token payload 的 ver 必須等於帳號目前的 tokenVersion。
 * 改密碼、重設密碼、變更角色會讓版本 +1；帳號被刪除則查無版本——兩者都使既有 token 立即失效。
 * demo-login 簽發的 demo token 沿用原有邏輯（只在 demo 模式有效，不比對版本）。
 */
@Component
public class TokenVersionChecker {

    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;

    public TokenVersionChecker(UserRepository userRepository, WorkerRepository workerRepository) {
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
    }

    public boolean isCurrent(AuthContext ctx) {
        if (ctx.demo()) return true;
        Optional<Integer> current = userRepository.findTokenVersion(ctx.sub())
                .or(() -> workerRepository.findTokenVersion(ctx.sub()));
        return current.isPresent() && current.get() == ctx.tokenVersion();
    }
}
