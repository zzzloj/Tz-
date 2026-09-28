<?php
// One-time export of the game world (game/1 data directories, cp1251) to
// content/ (UTF-8 JSON). tools/content/build.py turns content/ back into the
// files the engine reads; tools/content/verify.php checks the round trip.
//
//   php tools/content/export.php <game/1> <content dir>
//
// Rule: a value is split into named fields only where the split is exact
// (splitting and joining on the same delimiter); anything else is kept as the
// original string. Player records (u.*) from the 2007 world snapshot are not
// exported: they hold other people's passwords and IPs.
error_reporting(E_ALL);
require __DIR__ . '/schema.php';

[$_, $src, $out] = $argv + [null, null, null];
if (!$src || !$out) { fwrite(STDERR, "usage: export.php <game/1> <content>\n"); exit(2); }
$src = rtrim($src, '/');
$problems = [];

function u8($s) {
    global $problems;
    $r = @iconv('CP1251', 'UTF-8', $s);
    if ($r === false) { $problems[] = 'not cp1251: ' . substr($s, 0, 60); return mb_convert_encoding($s, 'UTF-8', 'Windows-1251'); }
    return $r;
}
function u8_deep($v) {
    if (is_string($v)) return u8($v);
    if (!is_array($v)) return $v;
    $r = [];
    foreach ($v as $k => $x) $r[is_string($k) ? u8($k) : $k] = u8_deep($x);
    return $r;
}
function put($path, $data) {
    @mkdir(dirname($path), 0777, true);
    if (!is_string($data)) {
        $data = json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION);
        if ($data === false) throw new RuntimeException("json: $path " . json_last_error_msg());
        $data .= "\n";
    }
    file_put_contents($path, $data);
}
function is_code($s) { return strpos($s, '<?') !== false; }
function files($dir) {
    $r = [];
    foreach (scandir($dir) as $f) if ($f[0] !== '.' && is_file("$dir/$f")) $r[] = $f;
    return $r;
}

// PHP data files (npc/, speak/): only files that are a plain array literal
// (strings, numbers, concatenation with the listed variables) are data.
function pure_array_file($path, $allowedVars) {
    $ok = [T_OPEN_TAG, T_CLOSE_TAG, T_WHITESPACE, T_VARIABLE, T_ARRAY, T_CONSTANT_ENCAPSED_STRING,
           T_DOUBLE_ARROW, T_LNUMBER, T_DNUMBER, T_COMMENT];
    foreach (token_get_all(file_get_contents($path)) as $t) {
        if (is_array($t)) {
            if (!in_array($t[0], $ok, true)) return false;
            if ($t[0] === T_VARIABLE && !in_array($t[1], $allowedVars, true)) return false;
        } elseif (!in_array($t, ['(', ')', ',', '=', ';', '.', '[', ']'], true)) return false;
    }
    return true;
}
function include_isolated($path, $var, $vars = []) {
    extract($vars);
    $$var = null;
    include $path;
    return $$var;
}

// ---- items/, items1/ -------------------------------------------------------
$stats = [];
foreach (['items' => 'items', 'items1' => 'items_dublon'] as $dir => $dest) {
    foreach (files("$src/$dir") as $f) {
        $raw = file_get_contents("$src/$dir/$f");
        if (is_code($raw)) { put("$out/raw/$dir/$f", u8($raw)); @$stats["raw/$dir"]++; continue; }
        put("$out/$dest/$f.json", item_to_json($f, u8($raw)));
        @$stats[$dest]++;
    }
}

// ---- npc/ ------------------------------------------------------------------
foreach (files("$src/npc") as $f) {
    $path = "$src/npc/$f";
    if (!pure_array_file($path, ['$npc'])) { put("$out/raw/npc/$f", u8(file_get_contents($path))); @$stats['raw/npc']++; continue; }
    $npc = include_isolated($path, 'npc');
    if (!is_array($npc)) { $problems[] = "npc/$f: not an array"; continue; }
    put("$out/npcs/$f.json", ['id' => $f] + entity_to_json(u8_deep($npc)));
    @$stats['npcs']++;
}

// ---- speak/ ----------------------------------------------------------------
foreach (files("$src/speak") as $f) {
    $path = "$src/speak/$f";
    $raw = file_get_contents($path);
    if (!is_code($raw)) { put("$out/raw/speak/$f", u8($raw)); @$stats['raw/speak']++; continue; }
    if (!pure_array_file($path, ['$dialog', '$sid'])) { put("$out/raw/speak/$f", u8($raw)); @$stats['raw/speak']++; continue; }
    $dialog = include_isolated($path, 'dialog', ['sid' => SID_SENTINEL_PHP]);
    if (!is_array($dialog)) { $problems[] = "speak/$f: not an array"; continue; }
    put("$out/dialogs/$f.json", ['id' => $f, 'topics' => dialog_to_json(u8_deep($dialog))]);
    @$stats['dialogs']++;
}

// ---- desc/ (help texts) ----------------------------------------------------
foreach (files("$src/desc") as $f) { put("$out/raw/desc/$f", u8(file_get_contents("$src/desc/$f"))); @$stats['raw/desc']++; }

// ---- locations: l_i (world at start) + l_f (description) -------------------
function load_ser($path) {
    $s = (string)@file_get_contents($path);
    if ($s === '') return null;
    $a = @unserialize($s);
    if ($a === false) $a = @unserialize(preg_replace_callback('/s:\d+:"(.*?)";/s', function ($m) { return 's:' . strlen($m[1]) . ':"' . $m[1] . '";'; }, $s));
    return $a === false ? false : $a;
}
$ids = array_unique(array_merge(files("$src/l_i"), files("$src/l_f")));
sort($ids, SORT_STRING);
$players = 0;
foreach ($ids as $id) {
    $loc = ['id' => $id];
    $state = file_exists("$src/l_i/$id") ? load_ser("$src/l_i/$id") : null;
    if ($state === false) { $problems[] = "l_i/$id: cannot unserialize"; continue; }
    if (is_array($state)) {
        // TZ_EXPORT_PLAYERS=1 keeps them, only for a local comparison with the original world.
        if (!getenv('TZ_EXPORT_PLAYERS'))
            foreach ($state['i'] ?? [] as $k => $v) if (is_string($k) && strncmp($k, 'u.', 2) === 0) { unset($state['i'][$k]); $players++; }
        $loc += location_to_json(u8_deep($state));
    } elseif (file_exists("$src/l_i/$id")) {
        $loc['state'] = null;   // empty l_i file
    }
    if (file_exists("$src/l_f/$id")) $loc['description'] = u8(file_get_contents("$src/l_f/$id"));
    put("$out/locations/$id.json", $loc);
    @$stats['locations']++;
}

// Case-insensitive file systems (macOS, Windows) cannot hold two ids that
// differ only in case.
foreach (['items', 'items_dublon', 'npcs', 'dialogs', 'locations'] as $d) {
    $seen = [];
    foreach (glob("$out/$d/*") as $p) { $k = strtolower(basename($p)); if (isset($seen[$k])) $problems[] = "case clash: $d/" . basename($p); $seen[$k] = 1; }
}

ksort($stats);
foreach ($stats as $k => $n) echo str_pad($k, 16) . "$n\n";
echo "players left out: $players\n";
foreach ($problems as $p) fwrite(STDERR, "PROBLEM $p\n");
exit($problems ? 1 : 0);
