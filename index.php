<?php
require_once 'config/db.php';
// Tidak ada auto-redirect, biarkan user melihat landing page dulu
?>
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
        <div class="container mx-auto px-4 md:px-6 py-4">
            <div class="flex justify-between items-center">
                <div class="text-xl md:text-2xl font-bold bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent">
                    🚀 SaaS Panel
                </div>
                <div class="space-x-3 md:space-x-4">
                    <?php if (isset($_SESSION['user_id'])): ?>
                        <?php if ($_SESSION['role'] == 'admin'): ?>
                            <a href="admin/index.php" class="px-3 md:px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 transition text-sm md:text-base">Dashboard</a>
                        <?php else: ?>
                            <a href="user/dashboard.php" class="px-3 md:px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 transition text-sm md:text-base">Dashboard</a>
                        <?php endif; ?>
                        <a href="auth/logout.php" class="px-3 md:px-4 py-2 rounded-lg border border-gray-600 hover:bg-gray-800 transition text-sm md:text-base">Logout</a>
                    <?php else: ?>
                        <a href="auth/login.php" class="px-3 md:px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 transition text-sm md:text-base">Login</a>
                        <a href="auth/register.php" class="px-3 md:px-4 py-2 rounded-lg border border-gray-600 hover:bg-gray-800 transition text-sm md:text-base">Register</a>
                    <?php endif; ?>
                </div>
            </div>
        </div>
    </nav>

    <!-- Hero Section -->
    <main>
        <div class="container mx-auto px-4 md:px-6 pt-24 md:pt-32 pb-16 md:pb-20">
            <div class="text-center fade-in">
                <h1 class="text-3xl md:text-5xl lg:text-6xl font-bold mb-4 md:mb-6 bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent">
                    Hosting & API Gateway Platform
                </h1>
                <p class="text-base md:text-xl text-gray-400 mb-6 md:mb-8 max-w-3xl mx-auto px-4">
                    Enterprise-grade hosting solution with built-in API gateway, server management, and real-time monitoring
                </p>
                <div class="flex flex-col sm:flex-row justify-center space-y-3 sm:space-y-0 sm:space-x-4 px-4">
                    <a href="auth/register.php" class="px-6 md:px-8 py-3 rounded-lg bg-blue-600 hover:bg-blue-700 transition font-semibold transform hover:scale-105 text-center">
                        Get Started Free
                    </a>
                    <a href="#features" class="px-6 md:px-8 py-3 rounded-lg border border-gray-600 hover:bg-gray-800 transition text-center">
                        Learn More
                    </a>
                </div>
            </div>

            <!-- Stats -->
            <div class="grid grid-cols-2 md:grid-cols-4 gap-4 md:gap-8 mt-12 md:mt-20">
                <div class="text-center">
                    <div class="text-2xl md:text-3xl font-bold text-blue-500">99.9%</div>
                    <div class="text-xs md:text-sm text-gray-400 mt-1">Uptime Guarantee</div>
                </div>
                <div class="text-center">
                    <div class="text-2xl md:text-3xl font-bold text-blue-500">24/7</div>
                    <div class="text-xs md:text-sm text-gray-400 mt-1">Support</div>
                </div>
                <div class="text-center">
                    <div class="text-2xl md:text-3xl font-bold text-blue-500">1M+</div>
                    <div class="text-xs md:text-sm text-gray-400 mt-1">API Requests</div>
                </div>
                <div class="text-center">
                    <div class="text-2xl md:text-3xl font-bold text-blue-500">50+</div>
                    <div class="text-xs md:text-sm text-gray-400 mt-1">Servers</div>
                </div>
            </div>
        </div>

        <!-- Features -->
        <div id="features" class="bg-[#1e293b]/50 py-16 md:py-20">
            <div class="container mx-auto px-4 md:px-6">
                <div class="text-center mb-10 md:mb-12">
                    <h2 class="text-2xl md:text-3xl font-bold mb-3 md:mb-4">Powerful Features</h2>
                    <p class="text-gray-400 max-w-2xl mx-auto text-sm md:text-base">Everything you need to manage your hosting infrastructure</p>
                </div>
                <div class="grid md:grid-cols-2 lg:grid-cols-3 gap-6 md:gap-8">
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
                    <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 hover:border-blue-500 transition-all card-hover">
                        <div class="text-4xl mb-4">⚡</div>
                        <h3 class="text-xl font-bold mb-2">High Performance</h3>
                        <p class="text-gray-400">Optimized servers with SSD storage and high RAM allocation</p>
                    </div>
                    <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 hover:border-blue-500 transition-all card-hover">
                        <div class="text-4xl mb-4">🔄</div>
                        <h3 class="text-xl font-bold mb-2">Auto Failover</h3>
                        <p class="text-gray-400">Automatic server switching for maximum uptime</p>
                    </div>
                    <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 hover:border-blue-500 transition-all card-hover">
                        <div class="text-4xl mb-4">📝</div>
                        <h3 class="text-xl font-bold mb-2">Activity Logging</h3>
                        <p class="text-gray-400">Complete audit trail of all system activities</p>
                    </div>
                </div>
            </div>
        </div>

        <!-- Pricing -->
        <div class="container mx-auto px-4 md:px-6 py-16 md:py-20">
            <div class="text-center mb-10 md:mb-12">
                <h2 class="text-2xl md:text-3xl font-bold mb-3 md:mb-4">Simple Pricing</h2>
                <p class="text-gray-400">Choose the perfect plan for your needs</p>
            </div>
            <div class="grid md:grid-cols-3 gap-6 md:gap-8">
                <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 text-center card-hover">
                    <h3 class="text-xl font-bold mb-2">Starter</h3>
                    <div class="text-3xl font-bold text-blue-500 mb-4">$29<span class="text-sm text-gray-400">/mo</span></div>
                    <ul class="space-y-2 mb-6 text-gray-400">
                        <li>✓ 1 Server</li>
                        <li>✓ 4GB RAM</li>
                        <li>✓ 50GB Storage</li>
                        <li>✓ Basic Support</li>
                    </ul>
                    <a href="auth/register.php" class="block px-4 py-2 rounded-lg border border-blue-600 hover:bg-blue-600 transition">Get Started</a>
                </div>
                <div class="bg-[#1e293b] p-6 rounded-xl border-2 border-blue-500 text-center card-hover relative">
                    <div class="absolute -top-3 left-1/2 transform -translate-x-1/2 bg-blue-600 px-3 py-1 rounded-full text-xs">Popular</div>
                    <h3 class="text-xl font-bold mb-2">Professional</h3>
                    <div class="text-3xl font-bold text-blue-500 mb-4">$79<span class="text-sm text-gray-400">/mo</span></div>
                    <ul class="space-y-2 mb-6 text-gray-400">
                        <li>✓ 5 Servers</li>
                        <li>✓ 16GB RAM</li>
                        <li>✓ 200GB Storage</li>
                        <li>✓ Priority Support</li>
                        <li>✓ API Gateway</li>
                    </ul>
                    <a href="auth/register.php" class="block px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 transition">Get Started</a>
                </div>
                <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 text-center card-hover">
                    <h3 class="text-xl font-bold mb-2">Enterprise</h3>
                    <div class="text-3xl font-bold text-blue-500 mb-4">$199<span class="text-sm text-gray-400">/mo</span></div>
                    <ul class="space-y-2 mb-6 text-gray-400">
                        <li>✓ Unlimited Servers</li>
                        <li>✓ 64GB RAM</li>
                        <li>✓ 1TB Storage</li>
                        <li>✓ 24/7 Support</li>
                        <li>✓ Custom Solutions</li>
                    </ul>
                    <a href="auth/register.php" class="block px-4 py-2 rounded-lg border border-blue-600 hover:bg-blue-600 transition">Contact Sales</a>
                </div>
            </div>
        </div>

        <!-- Footer -->
        <footer class="bg-[#1e293b] border-t border-gray-800 py-8 md:py-12">
            <div class="container mx-auto px-4 md:px-6">
                <div class="text-center text-gray-400 text-sm">
                    <p>&copy; 2024 SaaS Panel. All rights reserved.</p>
                </div>
            </div>
        </footer>
    </main>

    <script src="assets/script.js"></script>
</body>
</html>