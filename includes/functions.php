<?php
// Helper functions

function isLoggedIn() {
    return isset($_SESSION['user_id']);
}

function isAdmin() {
    return isset($_SESSION['role']) && $_SESSION['role'] === 'admin';
}

function redirect($url) {
    header("Location: " . BASE_URL . $url);
    exit();
}

function generateApiKey() {
    return 'sk_' . bin2hex(random_bytes(24));
}

function logActivity($conn, $user_id, $action, $details = null) {
    $ip = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    $stmt = mysqli_prepare($conn, "INSERT INTO activity_logs (user_id, action, details, ip_address) VALUES (?, ?, ?, ?)");
    mysqli_stmt_bind_param($stmt, "isss", $user_id, $action, $details, $ip);
    mysqli_stmt_execute($stmt);
}

function getTotalUsers($conn) {
    $result = mysqli_query($conn, "SELECT COUNT(*) as total FROM users WHERE role = 'user'");
    $row = mysqli_fetch_assoc($result);
    return $row['total'];
}

function getActiveUsers($conn) {
    $result = mysqli_query($conn, "SELECT COUNT(*) as total FROM users WHERE status = 'active'");
    $row = mysqli_fetch_assoc($result);
    return $row['total'];
}

function getTotalServers($conn) {
    $result = mysqli_query($conn, "SELECT COUNT(*) as total FROM servers");
    $row = mysqli_fetch_assoc($result);
    return $row['total'];
}

function getActiveServers($conn) {
    $result = mysqli_query($conn, "SELECT COUNT(*) as total FROM servers WHERE status = 'active'");
    $row = mysqli_fetch_assoc($result);
    return $row['total'];
}

function getTotalRAMUsage($conn) {
    $result = mysqli_query($conn, "SELECT SUM(ram_used) as used, SUM(ram_total) as total FROM servers");
    $row = mysqli_fetch_assoc($result);
    $used = $row['used'] ?? 0;
    $total = $row['total'] ?? 1;
    return ($used / $total) * 100;
}

function getTotalStorageUsage($conn) {
    $result = mysqli_query($conn, "SELECT SUM(storage_used) as used, SUM(storage_total) as total FROM servers");
    $row = mysqli_fetch_assoc($result);
    $used = $row['used'] ?? 0;
    $total = $row['total'] ?? 1;
    return ($used / $total) * 100;
}
?>