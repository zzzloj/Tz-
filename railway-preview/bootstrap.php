<?php
// PHP 5.4 removed the old long array aliases used throughout this archive.
$HTTP_GET_VARS = $_GET;
$HTTP_POST_VARS = $_POST;
$HTTP_COOKIE_VARS = $_COOKIE;
$HTTP_SERVER_VARS = $_SERVER;
$HTTP_SESSION_VARS = isset($_SESSION) ? $_SESSION : array();
