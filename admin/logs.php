<?php
require_once '../config/db.php';
require_once '../includes/admin-auth.php';

// Get logs
$logs_query = mysqli_query($conn, "SELECT * FROM activity_logs ORDER BY created_at DESC LIMIT 100");
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Activity Logs - SaaS Panel</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-[#0f172a] text-white">
    <div class="flex h-screen">
        <!-- Sidebar -->
        <div class="w-64 bg-[#1e293b] border-r border-gray-800 fixed h-full">
            <div class="p-6">
                <h2 class="text-2xl font-bold bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent">
                    SaaS Panel
                </h2>
            </div>
            <nav class="mt-6">
                <a href="index.php" class="flex items-center px-6 py-3 hover:bg-gray-800 transition">
                    <span class="mr-3">📊</span> Dashboard
                </a>
                <a href="users.php" class="flex items-center px-6 py-3 hover:bg-gray-800 transition">
                    <span class="mr-3">👥</span> Users
                </a>
                <a href="servers.php" class="flex items-center px-6 py-3 hover:bg-gray-800 transition">
                    <span class="mr-3">🖥️</span> Servers
                </a>
                <a href="logs.php" class="flex items-center px-6 py-3 bg-blue-600/20 border-r-4 border-blue-600">
                    <span class="mr-3">📝</span> Logs
                </a>
                <a href="../auth/logout.php" class="flex items-center px-6 py-3 hover:bg-gray-800 transition text-red-400">
                    <span class="mr-3">🚪</span> Logout
                </a>
            </nav>
        </div>

        <!-- Main Content -->
        <div class="flex-1 ml-64 p-8">
            <div class="mb-8">
                <h1 class="text-3xl font-bold">Activity Logs</h1>
                <p class="text-gray-400 mt-1">System activity history</p>
            </div>

            <div class="bg-[#1e293b] rounded-xl border border-gray-800 overflow-x-auto">
                <table class="w-full">
                    <thead class="bg-[#0f172a] border-b border-gray-800">
                        <tr>
                            <th class="px-6 py-3 text-left text-sm font-semibold">ID</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">User ID</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Action</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Details</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">IP Address</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Time</th>
                        </tr>
                    </thead>
                    <tbody>
                        <?php while ($log = mysqli_fetch_assoc($logs_query)): ?>
                        <tr class="border-b border-gray-800 hover:bg-gray-800/50 transition">
                            <td class="px-6 py-4">#<?= $log['id'] ?></td>
                            <td class="px-6 py-4"><?= $log['user_id'] ?? 'System' ?></td>
                            <td class="px-6 py-4"><?= htmlspecialchars($log['action']) ?></td>
                            <td class="px-6 py-4 text-sm text-gray-400"><?= htmlspecialchars($log['details'] ?? '-') ?></td>
                            <td class="px-6 py-4 text-sm"><?= $log['ip_address'] ?></td>
                            <td class="px-6 py-4 text-sm text-gray-400"><?= $log['created_at'] ?></td>
                        </tr>
                        <?php endwhile; ?>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</body>
</html>