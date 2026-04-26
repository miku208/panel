<?php
require_once 'config/db.php';

// Script to create admin user - HAPUS FILE INI SETELAH DIGUNAKAN!
$email = 'miku@gmail.com';
$password = 'Admin123!';
$full_name = 'Super Admin';

$hashed_password = password_hash($password, PASSWORD_DEFAULT);

$check = mysqli_query($conn, "SELECT id FROM users WHERE email = '$email'");
if (mysqli_num_rows($check) == 0) {
    $query = "INSERT INTO users (email, password, full_name, role, status) VALUES ('$email', '$hashed_password', '$full_name', 'admin', 'active')";
    if (mysqli_query($conn, $query)) {
        echo "✅ Admin user created successfully!<br>";
        echo "📧 Email: $email<br>";
        echo "🔑 Password: $password<br>";
        echo "<br>⚠️ Please delete this file after use!";
    } else {
        echo "Error: " . mysqli_error($conn);
    }
} else {
    echo "Admin user already exists!";
}
?>