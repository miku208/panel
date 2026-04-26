<?php
require_once '../config/db.php';
require_once '../includes/auth.php';
require_once '../includes/functions.php';

$user_id = $_SESSION['user_id'];

// Get user's assigned servers
$servers_query = mysqli_query($conn, "
    SELECT s.* FROM servers s 
    JOIN user_servers us ON s.id = us.server_id 
    WHERE us.user_id = $user_id
");

// Get user's API keys
$api_keys_query = mysqli_query($conn, "SELECT COUNT(*) as count FROM api_keys WHERE user_id = $user_id");
$api_keys_count = mysqli_fetch_assoc($api_keys_query)['count'];
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>User Dashboard - SaaS Panel</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="../assets/style.css">
</head>
<body class="bg-[#0f172a] text-white">
    <nav class="bg-[#1e293b] border-b border-gray-800">
        <div class="container mx-auto px-6 py-4">
            <div class="flex justify-between items-center">
                <div class="text-2xl font-bold bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent">
                    SaaS Panel
                </div>
                <div class="flex items-center space-x-4">
                    <span class="text-gray-300">Welcome, <?= htmlspecialchars($_SESSION['user_name']) ?></span>
                    <a href="api-key.php" class="px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 transition">API Keys</a>
                    <a href="../auth/logout.php" class="px-4 py-2 rounded-lg border border-gray-600 hover:bg-gray-800 transition">Logout</a>
                </div>
            </div>
        </div>
    </nav>

    <div class="container mx-auto px-6 py-8">
        <div class="mb-8">
            <h1 class="text-3xl font-bold">Dashboard</h1>
            <p class="text-gray-400 mt-1">Welcome to your hosting panel</p>
        </div>

        <!-- User Info Card -->
        <div class="grid md:grid-cols-3 gap-6 mb-8">
            <div class="bg-gradient-to-br from-blue-600/20 to-purple-600/20 p-6 rounded-xl border border-gray-800 card-hover">
                <div class="flex items-center justify-between">
                    <div>
                        <p class="text-gray-400 text-sm">Account Status</p>
                        <p class="text-2xl font-bold mt-2 text-green-400">Active</p>
                    </div>
                    <div class="text-4xl">✅</div>
                </div>
            </div>
            <div class="bg-gradient-to-br from-purple-600/20 to-pink-600/20 p-6 rounded-xl border border-gray-800 card-hover">
                <div class="flex items-center justify-between">
                    <div>
                        <p class="text-gray-400 text-sm">API Keys</p>
                        <p class="text-2xl font-bold mt-2"><?= $api_keys_count ?></p>
                    </div>
                    <div class="text-4xl">🔑</div>
                </div>
            </div>
            <div class="bg-gradient-to-br from-green-600/20 to-blue-600/20 p-6 rounded-xl border border-gray-800 card-hover">
                <div class="flex items-center justify-between">
                    <div>
                        <p class="text-gray-400 text-sm">Assigned Servers</p>
                        <p class="text-2xl font-bold mt-2"><?= mysqli_num_rows($servers_query) ?></p>
                    </div>
                    <div class="text-4xl">🖥️</div>
                </div>
            </div>
        </div>

        <!-- Assigned Servers -->
        <div class="bg-[#1e293b] rounded-xl border border-gray-800 p-6 mb-8">
            <h2 class="text-xl font-semibold mb-4">Your Servers</h2>
            <?php if (mysqli_num_rows($servers_query) > 0): ?>
                <div class="grid md:grid-cols-2 gap-4">
                    <?php while ($server = mysqli_fetch_assoc($servers_query)): ?>
                    <div class="bg-[#0f172a] p-4 rounded-lg border border-gray-800">
                        <div class="flex justify-between items-start mb-3">
                            <div>
                                <h3 class="font-semibold"><?= htmlspecialchars($server['name']) ?></h3>
                                <p class="text-sm text-gray-400"><?= $server['ip_address'] ?></p>
                            </div>
                            <span class="px-2 py-1 rounded text-xs bg-green-600/20 text-green-400">Active</span>
                        </div>
                        <div class="space-y-2">
                            <div>
                                <div class="flex justify-between text-sm mb-1">
                                    <span>RAM Usage</span>
                                    <span><?= $server['ram_used'] ?> / <?= $server['ram_total'] ?> GB</span>
                                </div>
                                <div class="w-full bg-gray-700 rounded-full h-2">
                                    <div class="bg-blue-600 h-2 rounded-full" style="width: <?= ($server['ram_used'] / max($server['ram_total'], 1)) * 100 ?>%"></div>
                                </div>
                            </div>
                            <div>
                                <div class="flex justify-between text-sm mb-1">
                                    <span>Storage Usage</span>
                                    <span><?= $server['storage_used'] ?> / <?= $server['storage_total'] ?> GB</span>
                                </div>
                                <div class="w-full bg-gray-700 rounded-full h-2">
                                    <div class="bg-purple-600 h-2 rounded-full" style="width: <?= ($server['storage_used'] / max($server['storage_total'], 1)) * 100 ?>%"></div>
                                </div>
                            </div>
                        </div>
                    </div>
                    <?php endwhile; ?>
                </div>
            <?php else: ?>
                <p class="text-gray-400 text-center py-4">No servers assigned yet. Contact admin for server access.</p>
            <?php endif; ?>
        </div>

        <!-- Quick Actions -->
        <div class="bg-[#1e293b] rounded-xl border border-gray-800 p-6">
            <h2 class="text-xl font-semibold mb-4">Quick Actions</h2>
            <div class="flex space-x-4">
                <a href="api-key.php" class="px-6 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg transition">Manage API Keys</a>
            </div>
        </div>
    </div>
</body>
</html>