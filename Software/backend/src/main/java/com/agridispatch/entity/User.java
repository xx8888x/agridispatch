package com.agridispatch.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * 系統使用者帳號實體
 */
@Entity
@Table(name = "users")
public class User {
    @Id
    private String username;
    /** 密碼雜湊（pbkdf2$...），舊版明碼於啟動時自動升級；永不序列化輸出 */
    @JsonIgnore
    private String password;
    private String name;
    private String role;
    private String refId;
    private String roleDesc;
    /**
     * Token 版本（規格 03 第 2、5 節）：簽發時寫入 token payload 的 ver，驗證時與此值比對，不同即 401。
     * 改密碼、重設密碼、變更角色時 +1，讓既有 token 立即失效。舊資料為 null 時視為 0。
     */
    @JsonIgnore
    @Column(name = "token_version")
    private Integer tokenVersion = 0;

    public User() {}

    public User(String username, String password, String name, String role, String refId, String roleDesc) {
        this.username = username;
        this.password = password;
        this.name = name;
        this.role = role;
        this.refId = refId;
        this.roleDesc = roleDesc;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    @JsonIgnore
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRole() { return role; }
    /** 角色變更時一併使舊 token 失效 */
    public void setRole(String role) {
        if (!Objects.equals(this.role, role)) bumpTokenVersion();
        this.role = role;
    }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public String getRoleDesc() { return roleDesc; }
    public void setRoleDesc(String roleDesc) { this.roleDesc = roleDesc; }

    @JsonIgnore
    public int getTokenVersion() { return tokenVersion == null ? 0 : tokenVersion; }

    /** 使此帳號已簽發的 token 全部失效 */
    public void bumpTokenVersion() { this.tokenVersion = getTokenVersion() + 1; }
}
