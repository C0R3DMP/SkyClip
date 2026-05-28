package com.acme.clipcascade.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Entity
@Table(name = "login_attempts", indexes = {
    @Index(name = "idx_username_timestamp", columnList = "username, timestamp"),
    @Index(name = "idx_ip_timestamp", columnList = "ip_address, timestamp"),
    @Index(name = "idx_username_ip_timestamp", columnList = "username, ip_address, timestamp")
})
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Username is required")
    @Column(nullable = false)
    private String username;

    @NotNull(message = "IP address is required")
    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @NotNull(message = "Attempt type is required")
    @Column(nullable = false, length = 50)
    private String attemptType;

    @NotNull(message = "Timestamp is required")
    @Column(nullable = false)
    private LocalDateTime timestamp;

    public LoginAttempt() {
    }

    public LoginAttempt(String username, String ipAddress, String attemptType, LocalDateTime timestamp) {
        this.username = username;
        this.ipAddress = ipAddress;
        this.attemptType = attemptType;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getAttemptType() {
        return attemptType;
    }

    public void setAttemptType(String attemptType) {
        this.attemptType = attemptType;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "LoginAttempt{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", ipAddress='" + ipAddress + '\'' +
                ", attemptType='" + attemptType + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
