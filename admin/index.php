<?php
require_once '../config/db.php';
require_once '../includes/admin-auth.php';
require_once '../includes/functions.php';

// Get statistics
$total_users = getTotalUsers($conn);
$active_users = getActiveUsers($conn);
$total_servers = getTotalServers($conn);
$active_servers = getActiveServers($conn);
$ram_usage = getTotalRAMUsage($conn);
$storage_usage = getTotalStorageUsage($conn);

// Get user growth data
$growth_query = "SELECT DATE_FORMAT(created_at, '%Y-%m') as month, COUNT(*) as count 
                 FROM users 
                 WHERE created_at >= DATE_SUB(NOW(), INTERVAL 6 MONTH)
                 GROUP BY DATE_FORMAT(created_at, '%Y-%m')
                 ORDER BY month ASC";
$growth_result = mysqli_query($conn, $growth_query);
$user_growth_labels = [];
$user_growth_data = [];
while ($row = mysqli_fetch_assoc($growth_result)) {
    $user_growth_labels[] = $row['month'];
    $user_growth_data[] = $row['count'];
}

// Get server usage data
$servers_query = mysqli_query($conn, "SELECT name, ram_used, ram_total, storage_used, storage_total FROM servers LIMIT 5");
$server_names = [];
$ram_data = [];
$storage_data = [];
while ($server = mysqli_fetch_assoc($servers_query)) {
    $server_names[] = $server['name'];
    $ram_data[] = ($server['ram_used'] / max($server['ram_total'], 1)) * 100;
    $storage_data[] = ($server['storage_used'] / max($server['storage_total'], 1)) * 100;
}

// Get recent activity
$activity_query = mysqli_query($conn, "SELECT * FROM activity_logs ORDER BY created_at DESC LIMIT 10");
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin Dashboard - SaaS Panel</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <link rel="stylesheet" href="../assets/style.css">
</head>
<body class="bg-[#0f172a] text-white">
    <div class="flex h-screen">
        <!-- Sidebar -->
        <div class="w-64 bg-[#1e293b] border-r border-gray-800 fixed h-full overflow-y-auto">
            <div class="p-6">
                <h2 class="text-2xl font-bold bg-gradient-to-r from-blue-500 to-purple-600 bg-clip-text text-transparent">
                    SaaS Panel
                </h2>
                <p class="text-sm text-gray-400 mt-2">Admin Portal</p>
            </div>
            <nav class="mt-6">
                <a href="index.php" class="flex items-center px-6 py-3 bg-blue-600/20 border-r-4 border-blue-600">
                    <span class="mr-3">📊</span> Dashboard
                </a>
                <a href="users.php" class="flex items-center px-6 py-3 hover:bg-gray-800 transition">
                    <span class="mr-3">👥</span> Users
                </a>
                <a href="servers.php" class="flex items-center px-6 py-3 hover:bg-gray-800 transition">
                    <span class="mr-3">🖥️</span> Servers
                </a>
                <a href="logs.php" class="flex items-center px-6 py-3 hover:bg-gray-800 transition">
                    <span class="mr-3">📝</span> Logs
                </a>
                <a href="../auth/logout.php" class="flex items-center px-6 py-3 hover:bg-gray-800 transition text-red-400">
                    <span class="mr-3">🚪</span> Logout
                </a>
            </nav>
        </div>

        <!-- Main Content -->
        <div class="flex-1 ml-64 overflow-y-auto">
            <div class="p-8">
                <div class="mb-8">
                    <h1 class="text-3xl font-bold">Dashboard Overview</h1>
                    <p class="text-gray-400 mt-1">Welcome back, <?= htmlspecialchars($_SESSION['user_name']) ?></p>
                </div>

                <!-- Stats Cards -->
                <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
                    <div class="bg-gradient-to-br from-blue-600/20 to-purple-600/20 p-6 rounded-xl border border-gray-800 card-hover">
                        <div class="flex justify-between items-start">
                            <div>
                                <p class="text-gray-400 text-sm">Total Users</p>
                                <p class="text-3xl font-bold mt-2"><?= number_format($total_users) ?></p>
                            </div>
                            <div class="text-3xl">👥</div>
                        </div>
                    </div>
                    <div class="bg-gradient-to-br from-green-600/20 to-blue-600/20 p-6 rounded-xl border border-gray-800 card-hover">
                        <div class="flex justify-between items-start">
                            <div>
                                <p class="text-gray-400 text-sm">Active Users</p>
                                <p class="text-3xl font-bold mt-2"><?= number_format($active_users) ?></p>
                            </div>
                            <div class="text-3xl">✅</div>
                        </div>
                    </div>
                    <div class="bg-gradient-to-br from-purple-600/20 to-pink-600/20 p-6 rounded-xl border border-gray-800 card-hover">
                        <div class="flex justify-between items-start">
                            <div>
                                <p class="text-gray-400 text-sm">Total Servers</p>
                                <p class="text-3xl font-bold mt-2"><?= number_format($total_servers) ?></p>
                            </div>
                            <div class="text-3xl">🖥️</div>
                        </div>
                    </div>
                    <div class="bg-gradient-to-br from-orange-600/20 to-red-600/20 p-6 rounded-xl border border-gray-800 card-hover">
                        <div class="flex justify-between items-start">
                            <div>
                                <p class="text-gray-400 text-sm">Active Servers</p>
                                <p class="text-3xl font-bold mt-2"><?= number_format($active_servers) ?></p>
                            </div>
                            <div class="text-3xl">🔵</div>
                        </div>
                    </div>
                </div>

                <!-- Charts -->
                <div class="grid lg:grid-cols-2 gap-8 mb-8">
                    <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800">
                        <h3 class="text-lg font-semibold mb-4">User Growth</h3>
                        <canvas id="userGrowthChart"></canvas>
                    </div>
                    <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800">
                        <h3 class="text-lg font-semibold mb-4">Resource Usage</h3>
                        <canvas id="resourceChart"></canvas>
                    </div>
                </div>

                <!-- Server Performance -->
                <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 mb-8">
                    <h3 class="text-lg font-semibold mb-4">Server Performance</h3>
                    <canvas id="serverChart" height="100"></canvas>
                </div>

                <!-- Recent Activity -->
                <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800">
                    <h3 class="text-lg font-semibold mb-4">Recent Activity</h3>
                    <div class="space-y-3">
                        <?php while ($activity = mysqli_fetch_assoc($activity_query)): ?>
                        <div class="flex justify-between items-center py-2 border-b border-gray-800">
                            <div>
                                <span class="font-medium">User #<?= $activity['user_id'] ?></span>
                                <span class="text-gray-400 ml-2"><?= htmlspecialchars($activity['action']) ?></span>
                            </div>
                            <div class="text-sm text-gray-500"><?= $activity['created_at'] ?></div>
                        </div>
                        <?php endwhile; ?>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
        // User Growth Chart
        const userCtx = document.getElementById('userGrowthChart').getContext('2d');
        new Chart(userCtx, {
            type: 'line',
            data: {
                labels: <?= json_encode($user_growth_labels) ?>,
                datasets: [{
                    label: 'New Users',
                    data: <?= json_encode($user_growth_data) ?>,
                    borderColor: '#3b82f6',
                    backgroundColor: 'rgba(59, 130, 246, 0.1)',
                    tension: 0.4,
                    fill: true
                }]
            },
            options: {
                responsive: true,
                plugins: {
                    legend: { labels: { color: '#fff' } }
                },
                scales: {
                    y: { grid: { color: '#334155' }, ticks: { color: '#fff' } },
                    x: { grid: { color: '#334155' }, ticks: { color: '#fff' } }
                }
            }
        });

        // Resource Usage Chart
        const resourceCtx = document.getElementById('resourceChart').getContext('2d');
        new Chart(resourceCtx, {
            type: 'doughnut',
            data: {
                labels: ['RAM Usage', 'Storage Usage'],
                datasets: [{
                    data: [<?= round($ram_usage) ?>, <?= round($storage_usage) ?>],
                    backgroundColor: ['#3b82f6', '#8b5cf6'],
                    borderWidth: 0
                }]
            },
            options: {
                responsive: true,
                plugins: {
                    legend: { labels: { color: '#fff' } }
                }
            }
        });

        // Server Performance Chart
        const serverCtx = document.getElementById('serverChart').getContext('2d');
        new Chart(serverCtx, {
            type: 'bar',
            data: {
                labels: <?= json_encode($server_names) ?>,
                datasets: [
                    {
                        label: 'RAM Usage (%)',
                        data: <?= json_encode($ram_data) ?>,
                        backgroundColor: '#3b82f6',
                        borderRadius: 8
                    },
                    {
                        label: 'Storage Usage (%)',
                        data: <?= json_encode($storage_data) ?>,
                        backgroundColor: '#8b5cf6',
                        borderRadius: 8
                    }
                ]
            },
            options: {
                responsive: true,
                plugins: {
                    legend: { labels: { color: '#fff' } }
                },
                scales: {
                    y: { 
                        grid: { color: '#334155' }, 
                        ticks: { color: '#fff' },
                        max: 100
                    },
                    x: { grid: { color: '#334155' }, ticks: { color: '#fff' } }
                }
            }
        });
    </script>
</body>
</html>