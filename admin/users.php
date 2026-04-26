<?php
require_once '../config/db.php';
require_once '../includes/admin-auth.php';
require_once '../includes/functions.php';

// Handle user actions
if (isset($_GET['action'])) {
    $user_id = (int)$_GET['id'];
    
    if ($_GET['action'] == 'delete') {
        mysqli_query($conn, "DELETE FROM users WHERE id = $user_id AND role != 'admin'");
        logActivity($conn, $_SESSION['user_id'], 'Deleted user', "User ID: $user_id");
        header('Location: users.php');
        exit();
    }
    
    if ($_GET['action'] == 'ban') {
        mysqli_query($conn, "UPDATE users SET status = 'banned' WHERE id = $user_id");
        logActivity($conn, $_SESSION['user_id'], 'Banned user', "User ID: $user_id");
        header('Location: users.php');
        exit();
    }
    
    if ($_GET['action'] == 'unban') {
        mysqli_query($conn, "UPDATE users SET status = 'active' WHERE id = $user_id");
        logActivity($conn, $_SESSION['user_id'], 'Unbanned user', "User ID: $user_id");
        header('Location: users.php');
        exit();
    }
    
    if ($_GET['action'] == 'make-admin') {
        mysqli_query($conn, "UPDATE users SET role = 'admin' WHERE id = $user_id");
        logActivity($conn, $_SESSION['user_id'], 'Promoted to admin', "User ID: $user_id");
        header('Location: users.php');
        exit();
    }
}

// Get all users
$users_query = mysqli_query($conn, "SELECT * FROM users ORDER BY created_at DESC");
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Users Management - SaaS Panel</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="../assets/style.css">
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
                <a href="users.php" class="flex items-center px-6 py-3 bg-blue-600/20 border-r-4 border-blue-600">
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
        <div class="flex-1 ml-64 p-8">
            <div class="mb-8">
                <h1 class="text-3xl font-bold">Users Management</h1>
                <p class="text-gray-400 mt-1">Manage all registered users</p>
            </div>

            <div class="bg-[#1e293b] rounded-xl border border-gray-800 overflow-x-auto">
                <table class="w-full">
                    <thead class="bg-[#0f172a] border-b border-gray-800">
                        <tr>
                            <th class="px-6 py-3 text-left text-sm font-semibold">ID</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Name</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Email</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Role</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Status</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Joined</th>
                            <th class="px-6 py-3 text-left text-sm font-semibold">Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <?php while ($user = mysqli_fetch_assoc($users_query)): ?>
                        <tr class="border-b border-gray-800 hover:bg-gray-800/50 transition">
                            <td class="px-6 py-4">#<?= $user['id'] ?></td>
                            <td class="px-6 py-4"><?= htmlspecialchars($user['full_name']) ?></td>
                            <td class="px-6 py-4"><?= htmlspecialchars($user['email']) ?></td>
                            <td class="px-6 py-4">
                                <span class="px-2 py-1 rounded text-xs <?= $user['role'] == 'admin' ? 'bg-purple-600/20 text-purple-400' : 'bg-blue-600/20 text-blue-400' ?>">
                                    <?= $user['role'] ?>
                                </span>
                            </td>
                            <td class="px-6 py-4">
                                <span class="px-2 py-1 rounded text-xs <?= $user['status'] == 'active' ? 'bg-green-600/20 text-green-400' : 'bg-red-600/20 text-red-400' ?>">
                                    <?= $user['status'] ?>
                                </span>
                            </td>
                            <td class="px-6 py-4 text-sm text-gray-400"><?= date('M d, Y', strtotime($user['created_at'])) ?></td>
                            <td class="px-6 py-4 space-x-2">
                                <?php if ($user['role'] != 'admin'): ?>
                                    <a href="?action=make-admin&id=<?= $user['id'] ?>" 
                                       class="text-blue-400 hover:text-blue-300 text-sm">Promote</a>
                                    <?php if ($user['status'] == 'active'): ?>
                                        <a href="?action=ban&id=<?= $user['id'] ?>" 
                                           class="text-orange-400 hover:text-orange-300 text-sm">Ban</a>
                                    <?php else: ?>
                                        <a href="?action=unban&id=<?= $user['id'] ?>" 
                                           class="text-green-400 hover:text-green-300 text-sm">Unban</a>
                                    <?php endif; ?>
                                    <a href="?action=delete&id=<?= $user['id'] ?>" 
                                       class="text-red-400 hover:text-red-300 text-sm"
                                       onclick="return confirm('Delete this user?')">Delete</a>
                                <?php else: ?>
                                    <span class="text-gray-500 text-sm">System Admin</span>
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