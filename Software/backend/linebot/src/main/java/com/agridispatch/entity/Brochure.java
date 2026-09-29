package com.agridispatch.entity;

import jakarta.persistence.*;

/**
 * 產品文宣 DM 實體：支援前後台即時串通、文宣上傳與前台多項目展示
 */
@Entity
@Table(name = "brochures")
public class Brochure {

    @Id
    private String id;

    private String title;
    private String subtitle;
    private String category;
    private String crop;
    private String region;
    private String targetAudience;
    private String badge;
    private String icon;
    private String themeColor;

    @Column(columnDefinition = "TEXT")
    private String coverImage;

    private int originalPrice;
    private int discountPrice;
    private int depositRate;
    private int depositAmount;
    private int hours;
    private int workers;
    private int days;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String featuresJson;

    @Column(columnDefinition = "TEXT")
    private String comparisonJson;

    private String testimonialName;
    private String testimonialTitle;

    @Column(columnDefinition = "TEXT")
    private String testimonialQuote;

    private String phone;
    private String serviceOffice;
    private String status; // active, draft
    private String createdAt;

    public Brochure() {}

    public Brochure(String id, String title, String subtitle, String category, String crop,
                    String region, String targetAudience, String badge, String icon, String themeColor,
                    String coverImage, int originalPrice, int discountPrice, int depositRate,
                    int depositAmount, int hours, int workers, int days, String description,
                    String featuresJson, String comparisonJson, String testimonialName,
                    String testimonialTitle, String testimonialQuote, String phone,
                    String serviceOffice, String status, String createdAt) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.category = category;
        this.crop = crop;
        this.region = region;
        this.targetAudience = targetAudience;
        this.badge = badge;
        this.icon = icon;
        this.themeColor = themeColor;
        this.coverImage = coverImage;
        this.originalPrice = originalPrice;
        this.discountPrice = discountPrice;
        this.depositRate = depositRate;
        this.depositAmount = depositAmount;
        this.hours = hours;
        this.workers = workers;
        this.days = days;
        this.description = description;
        this.featuresJson = featuresJson;
        this.comparisonJson = comparisonJson;
        this.testimonialName = testimonialName;
        this.testimonialTitle = testimonialTitle;
        this.testimonialQuote = testimonialQuote;
        this.phone = phone;
        this.serviceOffice = serviceOffice;
        this.status = status;
        this.createdAt = createdAt;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getCrop() { return crop; }
    public void setCrop(String crop) { this.crop = crop; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getTargetAudience() { return targetAudience; }
    public void setTargetAudience(String targetAudience) { this.targetAudience = targetAudience; }

    public String getBadge() { return badge; }
    public void setBadge(String badge) { this.badge = badge; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String themeColor) { this.themeColor = themeColor; }

    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }

    public int getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(int originalPrice) { this.originalPrice = originalPrice; }

    public int getDiscountPrice() { return discountPrice; }
    public void setDiscountPrice(int discountPrice) { this.discountPrice = discountPrice; }

    public int getDepositRate() { return depositRate; }
    public void setDepositRate(int depositRate) { this.depositRate = depositRate; }

    public int getDepositAmount() { return depositAmount; }
    public void setDepositAmount(int depositAmount) { this.depositAmount = depositAmount; }

    public int getHours() { return hours; }
    public void setHours(int hours) { this.hours = hours; }

    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getFeaturesJson() { return featuresJson; }
    public void setFeaturesJson(String featuresJson) { this.featuresJson = featuresJson; }

    public String getComparisonJson() { return comparisonJson; }
    public void setComparisonJson(String comparisonJson) { this.comparisonJson = comparisonJson; }

    public String getTestimonialName() { return testimonialName; }
    public void setTestimonialName(String testimonialName) { this.testimonialName = testimonialName; }

    public String getTestimonialTitle() { return testimonialTitle; }
    public void setTestimonialTitle(String testimonialTitle) { this.testimonialTitle = testimonialTitle; }

    public String getTestimonialQuote() { return testimonialQuote; }
    public void setTestimonialQuote(String testimonialQuote) { this.testimonialQuote = testimonialQuote; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getServiceOffice() { return serviceOffice; }
    public void setServiceOffice(String serviceOffice) { this.serviceOffice = serviceOffice; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
