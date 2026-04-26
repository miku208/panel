<?php
require_once '../config/db.php';
require_once '../includes/functions.php';

// Jika sudah login, redirect berdasarkan role
if (isLoggedIn()) {
    if (isAdmin()) {
        header('Location: ../admin/index.php');
    } else {
        header('Location: ../user/dashboard.php');
    }
    exit();
}

$error = '';

if ($_SERVER['REQUEST_METHOD'] == 'POST') {
    $email = mysqli_real_escape_string($conn, $_POST['email']);
    $password = $_POST['password'];
    
    $query = "SELECT * FROM users WHERE email = '$email'";
    $result = mysqli_query($conn, $query);
    
    if ($user = mysqli_fetch_assoc($result)) {
        if ($user['status'] == 'banned') {
            $error = "Your account has been banned!";
        } elseif (password_verify($password, $user['password'])) {
            $_SESSION['user_id'] = $user['id'];
            $_SESSION['role'] = $user['role'];
            $_SESSION['user_name'] = $user['full_name'];
            $_SESSION['user_email'] = $user['email'];
            
            logActivity($conn, $user['id'], 'User logged in', "IP: {$_SERVER['REMOTE_ADDR']}");
            
            if ($user['role'] == 'admin') {
                header('Location: ../admin/index.php');
            } else {
                header('Location: ../user/dashboard.php');
            }
            exit();
        } else {
            $error = "Invalid email or password!";
        }
    } else {
        $error = "Invalid email or password!";
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Login - SaaS Panel</title>
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
                <p class="text-gray-400 text-sm md:text-base">Login to your account</p>
            </div>
            
            <?php if ($error): ?>
                <div class="bg-red-500/10 border border-red-500 text-red-500 p-3 rounded-lg mb-4 text-sm">
                    <?= $error ?>
                </div>
            <?php endif; ?>
            
            <form method="POST" class="space-y-4">
                <div>
                    <label class="block text-sm font-medium mb-2">Email Address</label>
                    <input type="email" name="email" required 
                           class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500 transition">
                </div>
                <div>
                    <label class="block text-sm font-medium mb-2">Password</label>
                    <input type="password" name="password" required 
                           class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500 transition">
                </div>
                <button type="submit" 
                        class="w-full bg-blue-600 hover:bg-blue-700 py-2 rounded-lg font-semibold transition transform hover:scale-105">
                    Login
                </button>
            </form>
            
            <div class="mt-6 pt-4 border-t border-gray-800 text-center text-sm text-gray-400">
                Don't have an account? 
                <a href="register.php" class="text-blue-500 hover:underline">Register here</a>
            </div>
            <div class="mt-3 text-center text-xs text-gray-500">
                <a href="../index.php" class="hover:text-gray-400">← Back to Home</a>
            </div>
        </div>
    </div>
</body>
</html>