<?php
// Prepended to every request (auto_prepend_file). Deployment glue only: the
// game code runs on PHP 8 with the helpers in lib/ (legacy.php).

$legacyGameRoot = getenv('LEGACY_GAME_ROOT') ? getenv('LEGACY_GAME_ROOT') : '/data/game';
// PHP 5/7 numeric semantics and the transitional mysql_* API (see lib/).
require_once $legacyGameRoot . '/lib/legacy.php';

// The original login script fetches the game page over HTTP. Keep that
// request on loopback and attach preview authentication only to this call.
function legacy_internal_request($path) {
    if (!preg_match('~^[12]/g\.php\?site=connect2&~', $path)) return '';
    $port = getenv('PORT') ? getenv('PORT') : '8080';
    $internalPort = getenv('LEGACY_INTERNAL_PORT') ? getenv('LEGACY_INTERNAL_PORT') : $port;
    $auth = base64_encode(getenv('PREVIEW_USER') . ':' . getenv('PREVIEW_PASSWORD'));
    $context = stream_context_create(array('http' => array(
        'method' => 'GET',
        'header' => "Authorization: Basic " . $auth . "\r\nX-Legacy-Internal: 1\r\nConnection: close\r\n",
        'timeout' => 10,
        'follow_location' => 0,
    )));
    $response = @file_get_contents('http://127.0.0.1:' . $internalPort . '/' . $path, false, $context);
    return $response === false ? '' : $response;
}

// The preview password must never become the game's URL-based password.
// The old engine accepts at most 10 characters and exposes its own password
// in navigation URLs, so translate the test login to a distinct game secret.
foreach (array('_GET', '_POST') as $source) {
    $in = &$GLOBALS[$source];
    if (isset($in['login'], $in['p']) && is_string($in['login']) && is_string($in['p'])
        && getenv('PREVIEW_USER') !== false && getenv('PREVIEW_PASSWORD') !== false
        && preg_replace('/^u\./', '', strtolower($in['login'])) === strtolower(getenv('PREVIEW_USER'))
        && hash_equals(getenv('PREVIEW_PASSWORD'), $in['p'])) {
        $in['p'] = substr(hash_hmac('sha256', getenv('PREVIEW_PASSWORD'), (string)getenv('MYSQLPASSWORD')), 0, 10);
        if ($source === '_GET') {
            $_SERVER['QUERY_STRING'] = http_build_query($_GET);
        }
    }
    unset($in);
}

// wml.js sends login and registration as POST so passwords stay out of URLs,
// but the legacy scripts read those fields from $_GET.
if (isset($_SERVER['REQUEST_METHOD']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $_GET = $_GET + $_POST;
    $_REQUEST = $_REQUEST + $_POST;
}

// Modern browsers cannot display the game's WML pages directly.
require_once getenv('LEGACY_HTML_PHP') ? getenv('LEGACY_HTML_PHP') : '/opt/legacy-html.php';
ob_start('legacy_html_output');
// The archived gzip.php uses this request header to compress its own output.
// Keep its inner buffer uncompressed so the outer HTML adapter can read it.
$_SERVER['HTTP_ACCEPT_ENCODING'] = '';
$_SERVER['HTTP_TE'] = '';
