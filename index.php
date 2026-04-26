<?php require_once 'config/db.php'; ?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SaaS Panel - Hosting & API Gateway Platform</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="assets/style.css">
</head>
<body class="bg-[#0f172a] text-white">
    <!-- Navigation -->
    <nav class="bg-[#1e293b]/80 backdrop-blur-lg border-b border-gray-800 fixed w-full z-50">
        <div class="container mx-auto px-6 py-4">
            <div class="flex justify-between items-center">
                <div class="text-2xl font-bold bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent">
                    🚀 SaaS Panel
                </div>
                <div class="space-x-4">
                    <?php if (isset($_SESSION['user_id'])): ?>
                        <?php if ($_SESSION['role'] == 'admin'): ?>
                            <a href="admin/index.php" class="px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 transition">Dashboard</a>
                        <?php else: ?>
                            <a href="user/dashboard.php" class="px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 transition">Dashboard</a>
                        <?php endif; ?>
                        <a href="auth/logout.php" class="px-4 py-2 rounded-lg border border-gray-600 hover:bg-gray-800 transition">Logout</a>
                    <?php else: ?>
                        <a href="auth/login.php" class="px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 transition">Login</a>
                        <a href="auth/register.php" class="px-4 py-2 rounded-lg border border-gray-600 hover:bg-gray-800 transition">Register</a>
                    <?php endif; ?>
                </div>
            </div>
        </div>
    </nav>

    <!-- Hero Section -->
    <main class="pt-24">
        <div class="container mx-auto px-6 py-20">
            <div class="text-center fade-in">
                <h1 class="text-5xl md:text-6xl font-bold mb-6 bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent">
                    Hosting & API Gateway Platform
                </h1>
                <p class="text-xl text-gray-400 mb-8 max-w-3xl mx-auto">
                    Enterprise-grade hosting solution with built-in API gateway, server management, and real-time monitoring
                </p>
                <div class="flex justify-center space-x-4">
                    <a href="auth/register.php" class="px-8 py-3 rounded-lg bg-blue-600 hover:bg-blue-700 transition font-semibold transform hover:scale-105">
                        Get Started Free
                    </a>
                    <a href="#features" class="px-8 py-3 rounded-lg border border-gray-600 hover:bg-gray-800 transition">
                        Learn More
                    </a>
                </div>
            </div>

            <!-- Features -->
            <div id="features" class="grid md:grid-cols-3 gap-8 mt-24">
                <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 hover:border-blue-500 transition-all card-hover">
                    <div class="text-4xl mb-4">🔒</div>
                    <h3 class="text-xl font-bold mb-2">Secure API Gateway</h3>
                    <p class="text-gray-400">API key authentication with rate limiting and request logging</p>
                </div>
                <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 hover:border-blue-500 transition-all card-hover">
                    <div class="text-4xl mb-4">🖥️</div>
                    <h3 class="text-xl font-bold mb-2">Server Management</h3>
                    <p class="text-gray-400">Monitor RAM, storage, and server status in real-time</p>
                </div>
                <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 hover:border-blue-500 transition-all card-hover">
                    <div class="text-4xl mb-4">📊</div>
                    <h3 class="text-xl font-bold mb-2">Analytics Dashboard</h3>
                    <p class="text-gray-400">Comprehensive statistics and charts for your infrastructure</p>
                </div>
            </div>
        </div>
    </main>

    <script src="assets/script.js"></script>
</body>
</html>