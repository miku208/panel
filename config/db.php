<?php
// Database configuration for InfinityFree / Hosting
define('DB_HOST', 'sql123.infinityfree.com'); // Ganti dengan host database Anda
define('DB_USER', 'if0_12345678'); // Ganti dengan username database
define('DB_PASS', 'your_password'); // Ganti dengan password database
define('DB_NAME', 'if0_12345678_saaspanel'); // Ganti dengan nama database

// Create connection using MySQLi
$conn = mysqli_connect(DB_HOST, DB_USER, DB_PASS, DB_NAME);

// Check connection
if (!$conn) {
    die("Connection failed: " . mysqli_connect_error());
}

// Set charset to UTF-8
mysqli_set_charset($conn, "utf8mb4");

// Start session if not started
if (session_status() === PHP_SESSION_NONE) {
    session_start();
}

// Base URL - Sesuaikan dengan domain Anda
define('BASE_URL', 'http://yourdomain.infinityfreeapp.com');
?>