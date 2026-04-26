<?php
require_once '../config/db.php';
require_once '../includes/functions.php';

if (isLoggedIn()) {
    if (isAdmin()) {
        header('Location: ../admin/index.php');
    } else {
        header('Location: ../user/dashboard.php');
    }
    exit();
}

$error = '';
$success = '';

if ($_SERVER['REQUEST_METHOD'] == 'POST') {
    $email = mysqli_real_escape_string($conn, $_POST['email']);
    $password = $_POST['password'];
    $confirm_password = $_POST['confirm_password'];
    $full_name = mysqli_real_escape_string($conn, $_POST['full_name']);
    
    // Validate
    if ($password !== $confirm_password) {
        $error = "Passwords do not match!";
    } elseif (strlen($password) < 6) {
        $error = "Password must be at least 6 characters!";
    } elseif (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
        $error = "Invalid email format!";
    } else {
        // Check if email exists
        $check = mysqli_query($conn, "SELECT id FROM users WHERE email = '$email'");
        if (mysqli_num_rows($check) > 0) {
            $error = "Email already registered!";
        } else {
            $hashed_password = password_hash($password, PASSWORD_DEFAULT);
            $query = "INSERT INTO users (email, password, full_name, role, status) VALUES ('$email', '$hashed_password', '$full_name', 'user', 'active')";
            
            if (mysqli_query($conn, $query)) {
                $user_id = mysqli_insert_id($conn);
                logActivity($conn, $user_id, 'User registered', "Email: $email");
                $success = "Registration successful! Please login.";
            } else {
                $error = "Registration failed: " . mysqli_error($conn);
            }
        }
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Register - SaaS Panel</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="../assets/style.css">
</head>
<body class="bg-[#0f172a]">
    <div class="min-h-screen flex items-center justify-center p-4">
        <div class="bg-[#1e293b] p-6 md:p-8 rounded-xl shadow-2xl w-full max-w-md">
            <div class="text-center mb-6 md:mb-8">
                <a href="../index.php" class="text-2xl md:text-3xl font-bold bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent block mb-2">
                    SaaS Panel
                </a>
                <p class="text-gray-400 text-sm md:text-base">Create your account</p>
            </div>
            
            <?php if ($error): ?>
                <div class="bg-red-500/10 border border-red-500 text-red-500 p-3 rounded-lg mb-4 text-sm">
                    <?= $error ?>
                </div>
            <?php endif; ?>
            
            <?php if ($success): ?>
                <div class="bg-green-500/10 border border-green-500 text-green-500 p-3 rounded-lg mb-4 text-sm">
                    <?= $success ?>
                    <a href="login.php" class="text-green-400 underline ml-2">Login here</a>
                </div>
            <?php endif; ?>
            
            <form method="POST" class="space-y-4">
                <div>
                    <label class="block text-sm font-medium mb-2">Full Name</label>
                    <input type="text" name="full_name" required 
                           class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500 transition">
                </div>
                <div>
                    <label class="block text-sm font-medium mb-2">Email Address</label>
                    <input type="email" name="email" required 
                           class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500 transition">
                </div>
                <div>
                    <label class="block text-sm font-medium mb-2">Password</label>
                    <input type="password" name="password" required 
                           class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500 transition">
                    <p class="text-xs text-gray-500 mt-1">Minimum 6 characters</p>
                </div>
                <div>
                    <label class="block text-sm font-medium mb-2">Confirm Password</label>
                    <input type="password" name="confirm_password" required 
                           class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500 transition">
                </div>
                <button type="submit" 
                        class="w-full bg-blue-600 hover:bg-blue-700 py-2 rounded-lg font-semibold transition transform hover:scale-105">
                    Register
                </button>
            </form>
            
            <div class="mt-6 pt-4 border-t border-gray-800 text-center text-sm text-gray-400">
                Already have an account? 
                <a href="login.php" class="text-blue-500 hover:underline">Login</a>
            </div>
            <div class="mt-3 text-center text-xs text-gray-500">
                <a href="../index.php" class="hover:text-gray-400">← Back to Home</a>
            </div>
        </div>
    </div>
</body>
</html>