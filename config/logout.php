<?php
require_once '../config/db.php';
require_once '../includes/functions.php';

if (isset($_SESSION['user_id'])) {
    logActivity($conn, $_SESSION['user_id'], 'User logged out', '');
}

session_destroy();
header('Location: ../index.php');
exit();
?>