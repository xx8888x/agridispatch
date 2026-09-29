package com.agridispatch.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 農業人才儲備實體
 */
@Entity
@Table(name = "talents")
public class Talent {
    @Id
    private String id;
    private String name;
    private String category;
    private String categoryName;
    private String phone;
    private String experience;
    private boolean pdpaConsent;
    private String registeredAt;

    public Talent() {}

    public Talent(String id, String name, String category, String categoryName,
                  String phone, String experience, boolean pdpaConsent, String registeredAt) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.categoryName = categoryName;
        this.phone = phone;
        this.experience = experience;
        this.pdpaConsent = pdpaConsent;
        this.registeredAt = registeredAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getExperience() { return experience; }
    public void setExperience(String experience) { this.experience = experience; }

    public boolean isPdpaConsent() { return pdpaConsent; }
    public void setPdpaConsent(boolean pdpaConsent) { this.pdpaConsent = pdpaConsent; }

    public String getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(String registeredAt) { this.registeredAt = registeredAt; }
}
