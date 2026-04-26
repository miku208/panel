<?php
require_once __DIR__ . '/auth.php';

if (!isAdmin()) {
    redirect('/user/dashboard.php');
}
?>