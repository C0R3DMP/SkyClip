CREATE TABLE login_attempts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    attempt_type VARCHAR(50) NOT NULL,
    timestamp DATETIME NOT NULL,
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

CREATE INDEX idx_username_timestamp ON login_attempts(username, timestamp);
CREATE INDEX idx_ip_timestamp ON login_attempts(ip_address, timestamp);
CREATE INDEX idx_username_ip_timestamp ON login_attempts(username, ip_address, timestamp);
