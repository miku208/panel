<?php
require_once '../config/db.php';
require_once '../includes/admin-auth.php';
require_once '../includes/functions.php';

// Handle server add/edit
if ($_SERVER['REQUEST_METHOD'] == 'POST') {
    $name = mysqli_real_escape_string($conn, $_POST['name']);
    $ip_address = mysqli_real_escape_string($conn, $_POST['ip_address']);
    $ram_total = (int)$_POST['ram_total'];
    $storage_total = (int)$_POST['storage_total'];
    $status = mysqli_real_escape_string($conn, $_POST['status']);
    
    if (isset($_POST['server_id']) && $_POST['server_id'] > 0) {
        $server_id = (int)$_POST['server_id'];
        $query = "UPDATE servers SET name='$name', ip_address='$ip_address', ram_total=$ram_total, storage_total=$storage_total, status='$status' WHERE id=$server_id";
        logActivity($conn, $_SESSION['user_id'], 'Updated server', "Server: $name");
    } else {
        $query = "INSERT INTO servers (name, ip_address, ram_total, storage_total, status) VALUES ('$name', '$ip_address', $ram_total, $storage_total, '$status')";
        logActivity($conn, $_SESSION['user_id'], 'Added server', "Server: $name");
    }
    
    mysqli_query($conn, $query);
    redirect('/admin/servers.php');
}

// Handle server delete
if (isset($_GET['delete'])) {
    $server_id = (int)$_GET['delete'];
    mysqli_query($conn, "DELETE FROM servers WHERE id = $server_id");
    logActivity($conn, $_SESSION['user_id'], 'Deleted server', "Server ID: $server_id");
    redirect('/admin/servers.php');
}

// Handle status update
if (isset($_GET['status']) && isset($_GET['id'])) {
    $server_id = (int)$_GET['id'];
    $new_status = mysqli_real_escape_string($conn, $_GET['status']);
    mysqli_query($conn, "UPDATE servers SET status = '$new_status' WHERE id = $server_id");
    logActivity($conn, $_SESSION['user_id'], 'Changed server status', "Server ID: $server_id to $new_status");
    redirect('/admin/servers.php');
}

$servers_query = mysqli_query($conn, "SELECT * FROM servers ORDER BY created_at DESC");
$edit_server = null;
if (isset($_GET['edit'])) {
    $edit_id = (int)$_GET['edit'];
    $result = mysqli_query($conn, "SELECT * FROM servers WHERE id = $edit_id");
    $edit_server = mysqli_fetch_assoc($result);
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Servers Management - SaaS Panel</title>
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
                <a href="servers.php" class="flex items-center px-6 py-3 bg-blue-600/20 border-r-4 border-blue-600">
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
        <div class="flex-1 ml-64 p-8">
            <div class="mb-8">
                <h1 class="text-3xl font-bold">Servers Management</h1>
                <p class="text-gray-400 mt-1">Manage your server infrastructure</p>
            </div>

            <!-- Add/Edit Server Form -->
            <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 mb-8">
                <h2 class="text-xl font-semibold mb-4"><?= $edit_server ? 'Edit Server' : 'Add New Server' ?></h2>
                <form method="POST" class="grid md:grid-cols-2 gap-4">
                    <?php if ($edit_server): ?>
                        <input type="hidden" name="server_id" value="<?= $edit_server['id'] ?>">
                    <?php endif; ?>
                    <div>
                        <label class="block text-sm mb-2">Server Name</label>
                        <input type="text" name="name" required value="<?= $edit_server['name'] ?? '' ?>" 
                               class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500">
                    </div>
                    <div>
                        <label class="block text-sm mb-2">IP Address</label>
                        <input type="text" name="ip_address" required value="<?= $edit_server['ip_address'] ?? '' ?>" 
                               class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500">
                    </div>
                    <div>
                        <label class="block text-sm mb-2">RAM Total (GB)</label>
                        <input type="number" name="ram_total" required value="<?= $edit_server['ram_total'] ?? 0 ?>" 
                               class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500">
                    </div>
                    <div>
                        <label class="block text-sm mb-2">Storage Total (GB)</label>
                        <input type="number" name="storage_total" required value="<?= $edit_server['storage_total'] ?? 0 ?>" 
                               class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500">
                    </div>
                    <div>
                        <label class="block text-sm mb-2">Status</label>
                        <select name="status" class="w-full px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500">
                            <option value="active" <?= ($edit_server['status'] ?? '') == 'active' ? 'selected' : '' ?>>Active</option>
                            <option value="dead" <?= ($edit_server['status'] ?? '') == 'dead' ? 'selected' : '' ?>>Dead</option>
                            <option value="maintenance" <?= ($edit_server['status'] ?? '') == 'maintenance' ? 'selected' : '' ?>>Maintenance</option>
                        </select>
                    </div>
                    <div class="flex items-end">
                        <button type="submit" class="w-full bg-blue-600 hover:bg-blue-700 py-2 rounded-lg font-semibold transition">
                            <?= $edit_server ? 'Update Server' : 'Add Server' ?>
                        </button>
                        <?php if ($edit_server): ?>
                            <a href="servers.php" class="ml-2 w-full bg-gray-600 hover:bg-gray-700 py-2 rounded-lg font-semibold transition text-center">
                                Cancel
                            </a>
                        <?php endif; ?>
                    </div>
                </form>
            </div>

            <!-- Servers List -->
            <div class="bg-[#1e293b] rounded-xl border border-gray-800 overflow-hidden">
                <table class="w-full">
                    <thead class="bg-[#0f172a] border-b border-gray-800">
                        <tr>
                            <th class="px-6 py-3 text-left text-sm font-semibold">ID</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Name</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">IP Address</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">RAM</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Storage</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Status</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <?php while ($server = mysqli_fetch_assoc($servers_query)): ?>
                        <tr class="border-b border-gray-800 hover:bg-gray-800/50 transition">
                            <td class="px-6 py-4">#<?= $server['id'] ?></td>
                            <td class="px-6 py-4 font-medium"><?= htmlspecialchars($server['name']) ?></td>
                            <td class="px-6 py-4"><?= htmlspecialchars($server['ip_address']) ?></td>
                            <td class="px-6 py-4"><?= $server['ram_used'] ?> / <?= $server['ram_total'] ?> GB</td>
                            <td class="px-6 py-4"><?= $server['storage_used'] ?> / <?= $server['storage_total'] ?> GB</td>
                            <td class="px-6 py-4">
                                <span class="px-2 py-1 rounded text-xs <?= 
                                    $server['status'] == 'active' ? 'bg-green-600/20 text-green-400' : 
                                    ($server['status'] == 'dead' ? 'bg-red-600/20 text-red-400' : 'bg-yellow-600/20 text-yellow-400') ?>">
                                    <?= $server['status'] ?>
                                </span>
                            </td>
                            <td class="px-6 py-4 space-x-2">
                                <a href="?edit=<?= $server['id'] ?>" class="text-blue-400 hover:text-blue-300">Edit</a>
                                <a href="?delete=<?= $server['id'] ?>" class="text-red-400 hover:text-red-300" onclick="return confirm('Delete this server?')">Delete</a>
                                <?php if ($server['status'] != 'active'): ?>
                                    <a href="?status=active&id=<?= $server['id'] ?>" class="text-green-400 hover:text-green-300">Set Active</a>
                                <?php endif; ?>
                                <?php if ($server['status'] != 'dead'): ?>
                                    <a href="?status=dead&id=<?= $server['id'] ?>" class="text-red-400 hover:text-red-300">Mark Dead</a>
                                <?php endif; ?>
                            </td>
                        </tr>
                        <?php endwhile; ?>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</body>
</html>