<?php
// PHP 5.4 removed the old long array aliases used throughout this archive.
$HTTP_GET_VARS = $_GET;
$HTTP_POST_VARS = $_POST;
$HTTP_COOKIE_VARS = $_COOKIE;
$HTTP_SERVER_VARS = $_SERVER;
$HTTP_SESSION_VARS = isset($_SESSION) ? $_SESSION : array();

// Modern browsers cannot display the game's WML pages directly.
require_once '/opt/legacy-html.php';
ob_start('legacy_html_output');
// The archived gzip.php uses this request header to compress its own output.
// Keep its inner buffer uncompressed so the outer HTML adapter can read it.
$_SERVER['HTTP_ACCEPT_ENCODING'] = '';
$_SERVER['HTTP_TE'] = '';

// session_register() was removed in PHP 5.4; the archived pages still call it.
if (!function_exists('session_register')) {
    function session_register($name) {
        if (session_status() === PHP_SESSION_NONE) session_start();
        $_SESSION[$name] = isset($GLOBALS[$name]) ? $GLOBALS[$name] : null;
        return true;
    }
}
