<?php
// Wrap archived WML decks in a small HTML shell. The deck itself is passed to
// the browser untouched and rendered by wml.js (cards, variables, soft keys,
// timers), so the original game scripts stay unchanged.

function legacy_wml_to_utf8($output) {
    if (preg_match('//u', $output)) return $output;
    // Players can switch the game to windows-1251 output in settings.
    $converted = @iconv('Windows-1251', 'UTF-8//IGNORE', $output);
    return $converted === false ? $output : $converted;
}

function legacy_html_output($output) {
    // f_connect.php fetches g.php internally and streams its original WML.
    // Let the outer request wrap it once, after the legacy translate buffer.
    if (!empty($_SERVER['HTTP_X_LEGACY_INTERNAL'])) return $output;
    if (stripos($output, '<wml') === false || stripos($output, '<card') === false) return $output;

    $deck = json_encode(legacy_wml_to_utf8($output), JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT | JSON_UNESCAPED_UNICODE);
    if ($deck === false) return $output;
    $scriptPath = getenv('LEGACY_WML_JS') ? getenv('LEGACY_WML_JS') : '/opt/legacy-wml.js';
    $script = @file_get_contents($scriptPath);
    if ($script === false) return $output;

    header_remove('Content-Encoding');
    header_remove('Content-Length');
    header('Content-Type: text/html; charset=UTF-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: same-origin');
    header("Content-Security-Policy: default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'");

    return '<!doctype html><html lang="ru"><head><meta charset="utf-8">'
        . '<meta name="viewport" content="width=device-width, initial-scale=1">'
        . '<title>Территория Зла</title><style>'
        . ':root{color-scheme:dark}'
        . 'body{margin:0;background:#16191e;color:#f3f0e9;font:17px/1.55 system-ui,-apple-system,sans-serif}'
        . 'main{box-sizing:border-box;max-width:640px;margin:0 auto;padding:16px 16px 96px}'
        . 'h1{font-size:1.2rem;margin:4px 0 14px;color:#f0c475;overflow-wrap:anywhere}'
        . '.p{margin:0 0 12px;overflow-wrap:anywhere}'
        . 'a{color:#aed4ff;text-decoration:none}a:hover,a:focus{text-decoration:underline}'
        . 'a[data-key]::before{content:attr(data-key) " ";color:#7d8796;font-size:.8em}'
        . '.unavailable{color:#8f96a0;cursor:not-allowed}'
        . 'input,select{box-sizing:border-box;width:100%;max-width:360px;min-height:42px;padding:8px 11px;margin:4px 0 8px;color:#fff;background:#10151b;border:1px solid #748092;border-radius:8px;font:inherit}'
        . 'img{max-width:100%;image-rendering:pixelated}table{border-collapse:collapse}td{padding:2px 6px;vertical-align:top}'
        . 'nav{position:fixed;left:0;right:0;bottom:0;display:flex;flex-wrap:wrap;gap:6px;justify-content:center;padding:8px 8px calc(8px + env(safe-area-inset-bottom));background:#0f1216f2;border-top:1px solid #333a45}'
        . '.softkey{padding:7px 12px;border:1px solid #48505e;border-radius:18px;background:#232831;color:#f3f0e9;font-size:.92rem}'
        . '</style></head><body><main><h1 id="wml-title"></h1><div id="wml-root"></div></main>'
        . '<nav id="wml-bar" hidden></nav>'
        . '<script type="application/json" id="wml-src">' . $deck . '</script>'
        . '<script>' . $script . '</script></body></html>';
}
