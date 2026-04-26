<?php
require_once '../config/db.php';

header('Content-Type: application/json');

// This endpoint can be called by cron job or external monitoring
// Simulate checking server status and updating records

$servers_query = mysqli_query($conn, "SELECT * FROM servers");

$results = [];
while ($server = mysqli_fetch_assoc($servers_query)) {
    // Simulate checking server health
    $is_alive = ($server['status'] == 'active');
    
    // Simulate random usage changes
    $new_ram_used = rand(0, $server['ram_total']);
    $new_storage_used = rand(0, $server['storage_total']);
    
    // Update server stats
    mysqli_query($conn, "UPDATE servers SET 
        ram_used = $new_ram_used,
        storage_used = $new_storage_used,
        last_check = NOW()
        WHERE id = {$server['id']}");
    
    // Log status
    mysqli_query($conn, "INSERT INTO server_status_logs (server_id, ram_usage, storage_usage, status) 
        VALUES ({$server['id']}, $new_ram_used, $new_storage_used, '{$server['status']}')");
    
    $results[] = [
        'id' => $server['id'],
        'name' => $server['name'],
        'status' => $server['status'],
        'ram_usage' => "$new_ram_used/{$server['ram_total']} GB",
        'storage_usage' => "$new_storage_used/{$server['storage_total']} GB",
        'checked_at' => date('Y-m-d H:i:s')
    ];
}

echo json_encode([
    'success' => true,
    'message' => 'Server status checked',
    'servers' => $results
]);
?>