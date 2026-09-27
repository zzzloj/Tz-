<?php
// Render archived WML as a small, same-origin HTML page. The game logic and
// persistent files are left to the original PHP scripts.
function legacy_html_text($value) {
    $value = html_entity_decode($value, ENT_QUOTES, 'UTF-8');
    if (!preg_match('//u', $value)) {
        $converted = @iconv('Windows-1251', 'UTF-8//IGNORE', $value);
        if ($converted !== false) $value = $converted;
    }
    return htmlspecialchars($value, ENT_QUOTES, 'UTF-8');
}

function legacy_html_attributes($tag) {
    $attributes = array();
    preg_match_all('/([a-z][a-z0-9_-]*)\s*=\s*(?:"([^"]*)"|\'([^\']*)\')/i', $tag, $matches, PREG_SET_ORDER);
    foreach ($matches as $match) {
        $attributes[strtolower($match[1])] = html_entity_decode(
            isset($match[2]) && $match[2] !== '' ? $match[2] : $match[3], ENT_QUOTES, 'UTF-8'
        );
    }
    return $attributes;
}

function legacy_html_link($href) {
    $href = preg_replace('/[\r\n\t]+/', '', trim($href));
    if ($href === '' || preg_match('/[\x00-\x1f]/', $href)) return false;
    if (preg_match('~^(?:https?:)?//~i', $href)) {
        $host = parse_url($href, PHP_URL_HOST);
        if (!$host || strcasecmp($host, $_SERVER['HTTP_HOST']) !== 0) return false;
    } elseif (preg_match('~^[a-z][a-z0-9+.-]*:~i', $href)) {
        return false;
    }
    if (preg_match('~^/?(?:forum|sms)(?:/|$)~i', $href)) return false;
    return $href;
}

function legacy_html_output($output) {
    if (stripos($output, '<wml') === false || stripos($output, '<card') === false) return $output;
    header_remove('Content-Encoding');
    header('Content-Type: text/html; charset=UTF-8');
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: same-origin');
    header("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'");

    $title = 'Территория Зла';
    if (preg_match('/<card\b[^>]*>/i', $output, $card)) {
        $attrs = legacy_html_attributes($card[0]);
        if (!empty($attrs['title'])) $title = html_entity_decode($attrs['title'], ENT_QUOTES, 'UTF-8');
    }
    $body = preg_replace('~^.*?<card\b[^>]*>~is', '', $output, 1);
    $body = preg_replace('~</card>.*$~is', '', $body, 1);
    $parts = preg_split('/(<[^>]*>)/s', $body, -1, PREG_SPLIT_DELIM_CAPTURE);
    $html = '';
    foreach ($parts as $part) {
        if ($part === '') continue;
        if ($part[0] !== '<') {
            $html .= legacy_html_text($part);
            continue;
        }
        if (!preg_match('~^<\s*(/?)\s*([a-z][a-z0-9]*)\b~i', $part, $tag)) continue;
        $closing = $tag[1] === '/';
        $name = strtolower($tag[2]);
        $attrs = legacy_html_attributes($part);
        if ($name === 'br') { $html .= '<br>'; continue; }
        if (in_array($name, array('p', 'b', 'i', 'strong', 'em', 'small', 'div', 'select', 'option'))) {
            if ($closing) { $html .= '</' . $name . '>'; continue; }
            $html .= '<' . $name;
            if ($name === 'select' && isset($attrs['name'])) $html .= ' name="' . legacy_html_text($attrs['name']) . '"';
            if ($name === 'option' && isset($attrs['value'])) $html .= ' value="' . legacy_html_text($attrs['value']) . '"';
            $html .= '>';
        } elseif ($name === 'input' && !$closing) {
            $input = isset($attrs['name']) ? $attrs['name'] : '';
            if (!preg_match('/^[a-z0-9_]+$/i', $input)) continue;
            $type = isset($attrs['type']) && $attrs['type'] === 'password' ? 'password' : 'text';
            $html .= '<input type="' . $type . '" name="' . legacy_html_text($input) . '"';
            if (isset($attrs['maxlength'])) $html .= ' maxlength="' . intval($attrs['maxlength']) . '"';
            if (isset($attrs['value'])) $html .= ' value="' . legacy_html_text($attrs['value']) . '"';
            $html .= ' autocomplete="' . ($type === 'password' ? 'current-password' : 'username') . '">';
        } elseif ($name === 'a') {
            if ($closing) { $html .= '</a>'; continue; }
            $href = isset($attrs['href']) ? legacy_html_link($attrs['href']) : false;
            if ($href === false) { $html .= '<a class="unavailable" aria-disabled="true">'; continue; }
            if (strpos($href, '$(') !== false) {
                $html .= '<a href="#" class="wml-action" data-wml-href="' . legacy_html_text($href) . '">';
            } else {
                $html .= '<a href="' . legacy_html_text($href) . '">';
            }
        }
    }
    $safeTitle = legacy_html_text($title);
    return '<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
        . '<title>' . $safeTitle . '</title><style>'
        . 'body{margin:0;background:#16191e;color:#f3f0e9;font:17px/1.6 system-ui,sans-serif}'
        . 'main{box-sizing:border-box;max-width:680px;margin:32px auto;padding:24px;background:#232831;border:1px solid #424957;border-radius:16px}'
        . 'h1{font-size:1.45rem;margin:0 0 20px;color:#f0c475}p{margin:0 0 14px}a{color:#aed4ff;text-decoration:none}a:hover{text-decoration:underline}'
        . 'input,select{box-sizing:border-box;max-width:100%;min-height:42px;padding:8px 11px;margin:5px 0 12px;color:#fff;background:#10151b;border:1px solid #748092;border-radius:8px;font:inherit}'
        . '.unavailable{color:#99a0aa;cursor:not-allowed}button{font:inherit}@media(max-width:700px){main{margin:12px;padding:20px}}'
        . '</style></head><body><main><h1>' . $safeTitle . '</h1>' . $html . '</main><script>'
        . 'document.addEventListener("click",function(e){var a=e.target.closest("a.wml-action");if(!a)return;e.preventDefault();'
        . 'var url=new URL(a.dataset.wmlHref,location.href);if(url.origin!==location.origin)return;'
        . 'document.querySelectorAll("input[name],select[name]").forEach(function(input){if(/^[a-z0-9_]+$/i.test(input.name))sessionStorage.setItem("legacy."+input.name,input.value)});'
        . 'var f=document.createElement("form");f.method="post";f.action=url.pathname;'
        . 'url.searchParams.forEach(function(value,key){var field=document.createElement("input");field.type="hidden";field.name=key;'
        . 'field.value=value.replace(/\$\(([a-z0-9_]+)(?::escape)?\)/gi,function(_,name){var source=document.getElementsByName(name)[0];return source?source.value:(sessionStorage.getItem("legacy."+name)||"")});'
        . 'f.appendChild(field)});document.body.appendChild(f);f.submit()});'
        . '</script></body></html>';
}
