<?php
// Replace Latin look-alike letters inside Cyrillic words ("Aнтoниo" ->
// "Антонио", "кулaкaми" -> "кулаками"). The authors typed them by hand to
// save WAP traffic. Done in content/ (JSON strings, raw files) and in the
// string literals / inline HTML of the game code at the same time, so that
// comparisons between code and data keep matching.
//
//   php tools/content/normalize_lookalikes.php <repo> [--write]
//
// Without --write only reports. A word is changed only when every Latin
// letter in it has a Cyrillic twin; other mixed words are listed. The report
// also lists normalized words whose pure form already existed elsewhere
// before: a comparison between the two could start to match.
error_reporting(E_ALL);
[$_, $repo, $mode] = $argv + [null, null, ''];
$write = $mode === '--write';
const MAP = ['A' => 'А', 'a' => 'а', 'B' => 'В', 'C' => 'С', 'c' => 'с', 'E' => 'Е', 'e' => 'е', 'H' => 'Н',
             'K' => 'К', 'k' => 'к', 'M' => 'М', 'O' => 'О', 'o' => 'о', 'P' => 'Р', 'p' => 'р', 'T' => 'Т',
             'X' => 'Х', 'x' => 'х', 'y' => 'у'];
$changed = []; $skipped = []; $words = []; $evalHits = [];

function norm_text($s, $where) {
    return preg_replace_callback('/[A-Za-zА-Яа-яЁё]+/u', function ($m) use ($where, $s) {
        global $changed, $skipped, $words, $evalHits;
        $w = $m[0];
        if (!preg_match('/[А-Яа-яЁё]/u', $w) || !preg_match('/[A-Za-z]/', $w)) return $w;
        if (preg_match('/[^AaBCcEeHKkMOoPpTXxyА-Яа-яЁё]/u', $w)) { $skipped[$w][$where] = 1; return $w; }
        $n = strtr($w, MAP);
        $changed[$where] = ($changed[$where] ?? 0) + 1;
        $words[$w] = $n;
        if (strpos($s, 'eval:') !== false) $evalHits[$where] = 1;
        return $n;
    }, $s);
}

function walk($v, $where) {
    if (is_string($v)) return norm_text($v, $where);
    if (!is_array($v)) return $v;
    $r = [];
    foreach ($v as $k => $x) {
        if (is_string($k) && preg_match('/[А-Яа-яЁё]/u', $k) && preg_match('/[A-Za-z]/', $k)) $GLOBALS['skipped']["key $k"][$where] = 1;
        $r[$k] = walk($x, $where);
    }
    return $r;
}

$corpusBefore = '';
// content/*.json
$it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator("$repo/content", FilesystemIterator::SKIP_DOTS));
foreach ($it as $f) {
    $p = $f->getPathname(); $rel = substr($p, strlen($repo) + 1);
    $s = file_get_contents($p); $corpusBefore .= $s . "\n";
    if (substr($p, -5) === '.json') {
        $data = json_decode($s, true);
        $new = json_encode(walk($data, $rel), JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION) . "\n";
        if ($new !== $s && $write) file_put_contents($p, $new);
    } elseif (strpos($rel, 'content/raw/') === 0) {
        $new = raw_file($s, $rel);
        if ($new !== $s && $write) file_put_contents($p, $new);
    }
}

// PHP code in content/raw (UTF-8) and game/ (cp1251): literals and inline HTML only.
function php_code($src, $where) {
    $out = '';
    foreach (token_get_all($src) as $t) {
        if (is_array($t) && in_array($t[0], [T_CONSTANT_ENCAPSED_STRING, T_ENCAPSED_AND_WHITESPACE, T_INLINE_HTML], true)) $out .= norm_text($t[1], $where);
        else $out .= is_array($t) ? $t[1] : $t;
    }
    return $out;
}
function raw_file($s, $rel) {
    return strpos($s, '<?') !== false ? php_code($s, $rel) : norm_text($s, $rel);
}

$it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator("$repo/game", FilesystemIterator::SKIP_DOTS));
foreach ($it as $f) {
    $p = $f->getPathname(); $rel = substr($p, strlen($repo) + 1);
    if (!preg_match('~^game/(?!2/|forum/)~', $rel) || !preg_match('/\.(php|dat)$/', $p)) continue;
    $raw = file_get_contents($p);
    $s = iconv('CP1251', 'UTF-8', $raw);
    if ($s === false) { fwrite(STDERR, "skip (not cp1251): $rel\n"); continue; }
    $corpusBefore .= $s . "\n";
    if (strpos($s, '<?') === false) continue;
    $new = php_code($s, $rel);
    if ($new !== $s && $write) file_put_contents($p, iconv('UTF-8', 'CP1251', $new));
}

// Pure forms that existed before normalization next to mixed ones.
$collide = [];
foreach (array_unique($words) as $w => $n) {
    if (preg_match('/(?<![A-Za-zА-Яа-яЁё])' . preg_quote($n, '/') . '(?![A-Za-zА-Яа-яЁё])/u', $corpusBefore)) $collide[$n][] = $w;
}

arsort($changed);
$total = array_sum($changed);
echo "words changed: $total in " . count($changed) . " files, " . count($words) . " distinct\n";
echo "by area:\n";
$areas = [];
foreach ($changed as $f => $n) { $a = preg_replace('~^(content/[^/]+|game/[^/]+).*~', '$1', $f); $areas[$a] = ($areas[$a] ?? 0) + $n; }
arsort($areas); foreach ($areas as $a => $n) echo "  $a: $n\n";
echo "mixed words left as they are (" . count($skipped) . "):\n";
foreach (array_slice($skipped, 0, 40, true) as $w => $fs) echo "  $w  (" . implode(', ', array_slice(array_keys($fs), 0, 3)) . ")\n";
echo "strings with eval: code touched (" . count($evalHits) . "): " . implode(' ', array_keys($evalHits)) . "\n";
echo "normalized words whose pure form already existed (" . count($collide) . "):\n";
ksort($collide);
foreach ($collide as $n => $ws) echo "  $n <= " . implode(', ', $ws) . "\n";
