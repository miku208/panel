<?php
require_once '../config/db.php';
require_once '../includes/functions.php';

if (isLoggedIn()) {
    redirect('/index.php');
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
                redirect('/admin/index.php');
            } else {
                redirect('/user/dashboard.php');
            }
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
</head>
<body class="bg-[#0f172a]">
    <div class="min-h-screen flex items-center justify-center p-4">
        <div class="bg-[#1e293b] p-8 rounded-xl shadow-2xl w-full max-w-md">
            <div class="text-center mb-8">
                <h2 class="text-3xl font-bold bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent">
                    Welcome Back
                </h2>
                <p class="text-gray-400 mt-2">Login to your account</p>
            </div>
            
            <?php if ($error): ?>
                <div class="bg-red-500/10 border border-red-500 text-red-500 p-3 rounded-lg mb-4">
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
            
            <div class="mt-6 text-center text-sm text-gray-400">
                Don't have an account? 
                <a href="register.php" class="text-blue-500 hover:underline">Register</a>
            </div>
        </div>
    </div>
</body>
</html>