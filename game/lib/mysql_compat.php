<?php
// Transitional layer: the legacy mysql_* API (removed in PHP 7) on top of mysqli.
// The game code still calls mysql_query() etc.; replace with PDO during the
// refactor and delete this file. Only the functions the game uses are covered.
if (!function_exists('mysql_connect')) {

mysqli_report(MYSQLI_REPORT_OFF); // the old API returned false instead of throwing

if (!defined('MYSQL_ASSOC')) {
    define('MYSQL_ASSOC', MYSQLI_ASSOC);
    define('MYSQL_NUM', MYSQLI_NUM);
    define('MYSQL_BOTH', MYSQLI_BOTH);
}

function legacy_mysql_link($link = null) {
    if ($link instanceof mysqli) return $link;
    return isset($GLOBALS['__legacy_mysql_link']) ? $GLOBALS['__legacy_mysql_link'] : null;
}

function mysql_connect($server = null, $user = null, $password = null, $new_link = false, $flags = 0) {
    $host = $server === null ? ini_get('mysqli.default_host') : $server;
    $port = null; $socket = null;
    if (strpos($host, ':') !== false) {
        list($host, $rest) = explode(':', $host, 2);
        if (ctype_digit($rest)) $port = (int)$rest; else $socket = $rest;
    }
    if ($host === '') $host = 'localhost';
    $link = @mysqli_connect($host, $user, $password, '', $port ? $port : 3306, $socket);
    if (!$link) return false;
    // Game data and tables are windows-1251.
    mysqli_set_charset($link, 'cp1251');
    $GLOBALS['__legacy_mysql_link'] = $link;
    return $link;
}

function mysql_pconnect($server = null, $user = null, $password = null, $flags = 0) {
    return mysql_connect($server, $user, $password, false, $flags);
}

function mysql_select_db($db, $link = null) {
    $link = legacy_mysql_link($link);
    return $link ? mysqli_select_db($link, $db) : false;
}

function mysql_query($sql, $link = null) {
    $link = legacy_mysql_link($link);
    return $link ? mysqli_query($link, $sql) : false;
}

function mysql_fetch_array($result, $type = MYSQL_BOTH) {
    if (!($result instanceof mysqli_result)) return false;
    $row = mysqli_fetch_array($result, $type);
    return $row === null ? false : $row;
}

function mysql_fetch_assoc($result) { return mysql_fetch_array($result, MYSQL_ASSOC); }
function mysql_fetch_row($result) { return mysql_fetch_array($result, MYSQL_NUM); }

function mysql_num_rows($result) {
    return $result instanceof mysqli_result ? mysqli_num_rows($result) : false;
}

function mysql_result($result, $row, $field = 0) {
    if (!($result instanceof mysqli_result) || !mysqli_data_seek($result, $row)) return false;
    $data = mysqli_fetch_array($result, MYSQLI_BOTH);
    if (!$data) return false;
    return isset($data[$field]) ? $data[$field] : false;
}

function mysql_affected_rows($link = null) {
    $link = legacy_mysql_link($link);
    return $link ? mysqli_affected_rows($link) : -1;
}

function mysql_insert_id($link = null) {
    $link = legacy_mysql_link($link);
    return $link ? mysqli_insert_id($link) : 0;
}

function mysql_error($link = null) {
    $link = legacy_mysql_link($link);
    return $link ? mysqli_error($link) : (string)mysqli_connect_error();
}

function mysql_errno($link = null) {
    $link = legacy_mysql_link($link);
    return $link ? mysqli_errno($link) : (int)mysqli_connect_errno();
}

function mysql_real_escape_string($string, $link = null) {
    $link = legacy_mysql_link($link);
    return $link ? mysqli_real_escape_string($link, (string)$string) : addslashes((string)$string);
}

function mysql_escape_string($string) { return mysql_real_escape_string($string); }

function mysql_free_result($result) {
    if ($result instanceof mysqli_result) mysqli_free_result($result);
    return true;
}

function mysql_close($link = null) {
    $link = legacy_mysql_link($link);
    if (!$link) return false;
    if (isset($GLOBALS['__legacy_mysql_link']) && $GLOBALS['__legacy_mysql_link'] === $link) unset($GLOBALS['__legacy_mysql_link']);
    return mysqli_close($link);
}

}
