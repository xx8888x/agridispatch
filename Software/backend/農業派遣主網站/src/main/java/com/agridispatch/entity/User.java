package com.agridispatch.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
    public void setRole(String role) { this.role = role; }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public String getRoleDesc() { return roleDesc; }
    public void setRoleDesc(String roleDesc) { this.roleDesc = roleDesc; }
}
