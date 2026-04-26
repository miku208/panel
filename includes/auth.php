<?php
require_once __DIR__ . '/functions.php';

if (!isLoggedIn()) {
    redirect('/auth/login.php');
}
?>