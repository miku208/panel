-- Create database (if not exists)
CREATE DATABASE IF NOT EXISTS saas_panel;
USE saas_panel;

-- Users table
CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role ENUM('user', 'admin') DEFAULT 'user',
    status ENUM('active', 'banned') DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_role (role)
);

-- Servers table
CREATE TABLE servers (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    status ENUM('active', 'dead', 'maintenance') DEFAULT 'active',
    ram_total INT DEFAULT 0 COMMENT 'RAM in GB',
    ram_used INT DEFAULT 0,
    storage_total INT DEFAULT 0 COMMENT 'Storage in GB',
    storage_used INT DEFAULT 0,
    last_check TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status (status)
);

-- API Keys table
CREATE TABLE api_keys (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    api_key VARCHAR(64) UNIQUE NOT NULL,
    key_name VARCHAR(255) NOT NULL,
    requests_count INT DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    last_used TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_api_key (api_key),
    INDEX idx_user_id (user_id)
);

-- API Logs table
CREATE TABLE api_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    api_key VARCHAR(64),
    endpoint VARCHAR(500),
    method VARCHAR(10),
    request_data TEXT,
    response_code INT,
    response_data TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_api_key (api_key),
    INDEX idx_created_at (created_at)
);

-- Activity Logs table
CREATE TABLE activity_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    action VARCHAR(255),
    details TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
);

-- User Subscriptions (simple plan)
CREATE TABLE user_servers (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    server_id INT NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE CASCADE,
    UNIQUE KEY unique_assignment (user_id, server_id)
);

-- Insert default admin account (password: admin123)
INSERT INTO users (email, password, full_name, role) VALUES 
('admin@saas.com', '$2y$10$YourHashedPasswordHere', 'Super Admin', 'admin');

-- Insert sample servers
INSERT INTO servers (name, ip_address, status, ram_total, ram_used, storage_total, storage_used) VALUES 
('Main Server', '192.168.1.100', 'active', 16, 8, 500, 250),
('Backup Server', '192.168.1.101', 'active', 8, 3, 250, 100),
('Development Server', '192.168.1.102', 'maintenance', 4, 0, 100, 0);

-- Insert sample user
INSERT INTO users (email, password, full_name, role) VALUES 
('user@example.com', '$2y$10$demoHashedPassword', 'Demo User', 'user');