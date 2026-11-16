package com.documind.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Multi-turn conversational session owned by an authenticated user.
 */
@Entity
@Table(name = "chat_sessions")
public class ChatSessionEntity implements Persistable<UUID> {

    @Id
    @AssignedOrRandomUuid
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "user_id", nullable = false, insertable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onPrePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onPreUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UserEntity getUser() {
        return user;
    }

    public void setUser(UserEntity user) {
        this.user = user;
        this.userId = JpaProxyUtils.extractId(user);
    }

    public UUID getUserId() {
        return userId != null ? userId : JpaProxyUtils.extractId(user);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || JpaProxyUtils.effectiveClass(this) != JpaProxyUtils.effectiveClass(o)) {
            return false;
        }
        ChatSessionEntity that = (ChatSessionEntity) o;
        UUID thisId = JpaProxyUtils.extractId(this);
        UUID thatId = JpaProxyUtils.extractId(that);
        return thisId != null && Objects.equals(thisId, thatId);
    }

    @Override
    public int hashCode() {
        return JpaProxyUtils.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "ChatSessionEntity{id=" + id + ", title='" + title + "'}";
    }
}
