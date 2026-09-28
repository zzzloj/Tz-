<?php
// PHP 5.4 removed the old long array aliases used throughout this archive.
// The original login script fetches the game page over HTTP. Keep that
// request on loopback and attach preview authentication only to this call.
function legacy_internal_request($path) {
    if (!preg_match('~^[12]/g\.php\?site=connect2&~', $path)) return '';
    $port = getenv('PORT') ? getenv('PORT') : '8080';
    $auth = base64_encode(getenv('PREVIEW_USER') . ':' . getenv('PREVIEW_PASSWORD'));
    $context = stream_context_create(array('http' => array(
        'method' => 'GET',
        'header' => "Authorization: Basic " . $auth . "\r\nX-Legacy-Internal: 1\r\nConnection: close\r\n",
        'timeout' => 10,
        'follow_location' => 0,
    )));
    $response = @file_get_contents('http://127.0.0.1:' . $port . '/' . $path, false, $context);
    return $response === false ? '' : $response;
}
// The preview password must never become the game's URL-based password.
// The old engine accepts at most 10 characters and exposes its own password
// in navigation URLs, so translate the test login to a distinct game secret.
// wml.js sends links as GET (like a WAP phone), method="post" forms as POST.
foreach (array('_GET', '_POST') as $source) {
    $in = &$GLOBALS[$source];
    if (isset($in['login'], $in['p']) && is_string($in['login']) && is_string($in['p'])
        && preg_replace('/^u\./', '', strtolower($in['login'])) === strtolower(getenv('PREVIEW_USER'))
        && getenv('PREVIEW_PASSWORD') !== false
        && function_exists('hash_equals')
        && hash_equals(getenv('PREVIEW_PASSWORD'), $in['p'])) {
        $in['p'] = substr(hash_hmac('sha256', getenv('PREVIEW_PASSWORD'), getenv('MYSQLPASSWORD')), 0, 10);
        if ($source === '_GET') {
            $_SERVER['QUERY_STRING'] = http_build_query($_GET);
        }
    }
    unset($in);
}
// The HTML adapter turns every WML link into a same-origin POST so values do
// not end up in URLs, but many legacy scripts read $_GET directly.
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $_GET = $_GET + $_POST;
    $_REQUEST = $_REQUEST + $_POST;
}
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
