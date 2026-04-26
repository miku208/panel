<?php
require_once '../config/db.php';
require_once '../includes/functions.php';

header('Content-Type: application/json');

// Get API key from header
$headers = getallheaders();
$api_key = $headers['X-API-Key'] ?? $_GET['api_key'] ?? '';

if (empty($api_key)) {
    http_response_code(401);
    echo json_encode(['error' => 'API key required. Please provide X-API-Key header']);
    exit();
}

// Validate API key
$query = "SELECT ak.*, u.id as user_id, u.status as user_status 
          FROM api_keys ak 
          JOIN users u ON ak.user_id = u.id 
          WHERE ak.api_key = '$api_key' AND ak.is_active = 1";
$result = mysqli_query($conn, $query);
$key_data = mysqli_fetch_assoc($result);

if (!$key_data) {
    http_response_code(401);
    echo json_encode(['error' => 'Invalid or inactive API key']);
    exit();
}

if ($key_data['user_status'] != 'active') {
    http_response_code(403);
    echo json_encode(['error' => 'Your account is not active']);
    exit();
}

// Update request count
mysqli_query($conn, "UPDATE api_keys SET requests_count = requests_count + 1, last_used = NOW() WHERE id = {$key_data['id']}");

// Get endpoint
$endpoint = $_GET['endpoint'] ?? '';

// Simulate server response based on endpoint
$response_data = [];
$response_code = 200;

switch ($endpoint) {
    case 'status':
        // Get user's servers
        $servers_query = mysqli_query($conn, "
            SELECT s.name, s.ip_address, s.ram_used, s.ram_total, s.storage_used, s.storage_total, s.status 
            FROM servers s
            JOIN user_servers us ON s.id = us.server_id
            WHERE us.user_id = {$key_data['user_id']}
        ");
        $servers = [];
        while ($server = mysqli_fetch_assoc($servers_query)) {
            $servers[] = $server;
        }
        $response_data = [
            'status' => 'success',
            'user_id' => $key_data['user_id'],
            'servers' => $servers,
            'timestamp' => date('Y-m-d H:i:s')
        ];
        break;
        
    case 'info':
        $response_data = [
            'status' => 'success',
            'message' => 'API Gateway is running',
            'version' => '1.0.0',
            'endpoints' => ['status', 'info', 'health']
        ];
        break;
        
    case 'health':
        $response_data = [
            'status' => 'healthy',
            'timestamp' => date('Y-m-d H:i:s')
        ];
        break;
        
    default:
        $response_data = [
            'error' => 'Invalid endpoint',
            'available_endpoints' => ['status', 'info', 'health']
        ];
        $response_code = 404;
        break;
}

// Log the request
$method = $_SERVER['REQUEST_METHOD'];
$request_data = json_encode($_REQUEST);
$ip = $_SERVER['REMOTE_ADDR'];
$response_json = json_encode($response_data);

$log_query = "INSERT INTO api_logs (user_id, api_key, endpoint, method, request_data, response_code, response_data, ip_address) 
              VALUES ({$key_data['user_id']}, '$api_key', '$endpoint', '$method', '$request_data', $response_code, '" . mysqli_real_escape_string($conn, $response_json) . "', '$ip')";
mysqli_query($conn, $log_query);

http_response_code($response_code);
echo json_encode($response_data);
?>