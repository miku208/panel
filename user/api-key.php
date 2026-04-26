<?php
require_once '../config/db.php';
require_once '../includes/auth.php';
require_once '../includes/functions.php';

$user_id = $_SESSION['user_id'];

// Generate new API key
if (isset($_POST['generate'])) {
    $key_name = mysqli_real_escape_string($conn, $_POST['key_name']);
    $api_key = generateApiKey();
    
    $query = "INSERT INTO api_keys (user_id, api_key, key_name) VALUES ($user_id, '$api_key', '$key_name')";
    if (mysqli_query($conn, $query)) {
        logActivity($conn, $user_id, 'Generated API key', "Key name: $key_name");
        $success = "API key generated successfully!";
    }
}

// Delete API key
if (isset($_GET['delete'])) {
    $key_id = (int)$_GET['delete'];
    mysqli_query($conn, "DELETE FROM api_keys WHERE id = $key_id AND user_id = $user_id");
    logActivity($conn, $user_id, 'Deleted API key', "Key ID: $key_id");
    header('Location: api-key.php');
    exit();
}

// Toggle API key status
if (isset($_GET['toggle'])) {
    $key_id = (int)$_GET['toggle'];
    $result = mysqli_query($conn, "SELECT is_active FROM api_keys WHERE id = $key_id AND user_id = $user_id");
    $key = mysqli_fetch_assoc($result);
    $new_status = $key['is_active'] ? 0 : 1;
    mysqli_query($conn, "UPDATE api_keys SET is_active = $new_status WHERE id = $key_id");
    header('Location: api-key.php');
    exit();
}

$api_keys = mysqli_query($conn, "SELECT * FROM api_keys WHERE user_id = $user_id ORDER BY created_at DESC");
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>API Keys - SaaS Panel</title>
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
                    <a href="dashboard.php" class="px-4 py-2 rounded-lg hover:bg-gray-800 transition">Dashboard</a>
                    <a href="../auth/logout.php" class="px-4 py-2 rounded-lg border border-gray-600 hover:bg-gray-800 transition">Logout</a>
                </div>
            </div>
        </div>
    </nav>

    <div class="container mx-auto px-6 py-8">
        <div class="mb-8">
            <h1 class="text-3xl font-bold">API Key Management</h1>
            <p class="text-gray-400 mt-1">Manage your API keys for accessing the gateway</p>
        </div>

        <?php if (isset($success)): ?>
            <div class="bg-green-500/10 border border-green-500 text-green-500 p-4 rounded-lg mb-6">
                <?= $success ?>
            </div>
        <?php endif; ?>

        <!-- Generate New Key -->
        <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 mb-8">
            <h2 class="text-xl font-semibold mb-4">Generate New API Key</h2>
            <form method="POST" class="flex gap-4">
                <input type="text" name="key_name" placeholder="Key name (e.g., Production App)" required 
                       class="flex-1 px-4 py-2 bg-[#0f172a] border border-gray-700 rounded-lg focus:outline-none focus:border-blue-500">
                <button type="submit" name="generate" class="px-6 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg font-semibold transition">
                    Generate Key
                </button>
            </form>
        </div>

        <!-- Existing Keys -->
        <div class="bg-[#1e293b] rounded-xl border border-gray-800 overflow-hidden">
            <div class="p-6 border-b border-gray-800">
                <h2 class="text-xl font-semibold">Your API Keys</h2>
            </div>
            <div class="divide-y divide-gray-800">
                <?php if (mysqli_num_rows($api_keys) > 0): ?>
                    <?php while ($key = mysqli_fetch_assoc($api_keys)): ?>
                    <div class="p-6">
                        <div class="flex justify-between items-start mb-3">
                            <div>
                                <h3 class="font-semibold text-lg"><?= htmlspecialchars($key['key_name']) ?></h3>
                                <code class="text-sm text-gray-400 bg-[#0f172a] px-2 py-1 rounded mt-1 inline-block">
                                    <?= $key['api_key'] ?>
                                </code>
                                <div class="text-sm text-gray-400 mt-2">
                                    Requests: <?= number_format($key['requests_count']) ?> | 
                                    Created: <?= date('M d, Y', strtotime($key['created_at'])) ?>
                                    <?php if ($key['last_used']): ?> | Last used: <?= date('M d, Y', strtotime($key['last_used'])) ?><?php endif; ?>
                                </div>
                            </div>
                            <div class="flex space-x-2">
                                <button onclick="copyToClipboard('<?= $key['api_key'] ?>')" 
                                        class="px-3 py-1 bg-gray-700 hover:bg-gray-600 rounded text-sm transition">
                                    Copy
                                </button>
                                <a href="?toggle=<?= $key['id'] ?>" 
                                   class="px-3 py-1 rounded text-sm transition <?= $key['is_active'] ? 'bg-yellow-600/20 text-yellow-400 hover:bg-yellow-600/30' : 'bg-green-600/20 text-green-400 hover:bg-green-600/30' ?>">
                                    <?= $key['is_active'] ? 'Disable' : 'Enable' ?>
                                </a>
                                <a href="?delete=<?= $key['id'] ?>" 
                                   class="px-3 py-1 bg-red-600/20 text-red-400 hover:bg-red-600/30 rounded text-sm transition"
                                   onclick="return confirm('Delete this API key?')">
                                    Delete
                                </a>
                            </div>
                        </div>
                    </div>
                    <?php endwhile; ?>
                <?php else: ?>
                    <div class="p-6 text-center text-gray-400">
                        No API keys generated yet. Create your first key above.
                    </div>
                <?php endif; ?>
            </div>
        </div>

        <!-- API Usage Example -->
        <div class="bg-[#1e293b] p-6 rounded-xl border border-gray-800 mt-8">
            <h2 class="text-xl font-semibold mb-4">How to Use Your API Key</h2>
            <div class="bg-[#0f172a] p-4 rounded-lg">
                <p class="text-sm text-gray-400 mb-2">Example request using curl:</p>
                <code class="text-sm text-green-400 block">
                    curl -X GET https://yourdomain.com/api/gateway.php?endpoint=status \<br>
                    &nbsp;&nbsp;-H "X-API-Key: YOUR_API_KEY_HERE"
                </code>
                <p class="text-sm text-gray-400 mt-3">The API gateway will forward your request to the assigned server and return the response.</p>
            </div>
        </div>
    </div>

    <script>
    function copyToClipboard(text) {
        navigator.clipboard.writeText(text).then(() => {
            showNotification('API key copied to clipboard!', 'success');
        });
    }
    
    function showNotification(message, type) {
        const notification = document.createElement('div');
        notification.className = `fixed bottom-4 right-4 p-4 rounded-lg shadow-lg fade-in ${
            type === 'success' ? 'bg-green-500' : 'bg-red-500'
        } text-white`;
        notification.textContent = message;
        document.body.appendChild(notification);
        setTimeout(() => notification.remove(), 3000);
    }
    </script>
</body>
</html>