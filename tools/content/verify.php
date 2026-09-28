<?php
// Round-trip check: the data files build.py produced from content/ are the
// same, for the engine, as the original ones.
//
//   php tools/content/verify.php <original game/1> <built game/1>
//
// items/, items1/: bytes equal (a final line break of the original dropped).
// npc/, speak/:    bytes equal, or the arrays they define are identical (===).
// desc/, l_f/:     bytes equal.
// l_i/:            unserialized arrays identical (the original repaired with
//                  the engine's calcser if needed; players u.* removed, they
//                  are not exported). l_t/ must equal the built l_i/.
error_reporting(E_ALL & ~E_WARNING & ~E_NOTICE & ~E_DEPRECATED);
[$_, $orig, $built] = $argv + [null, null, null];
if (!$orig || !$built) { fwrite(STDERR, "usage: verify.php <original game/1> <built game/1>\n"); exit(2); }
$fail = []; $checked = 0;

function names($dir) { $r = []; foreach (@scandir($dir) ?: [] as $f) if ($f[0] !== '.' && is_file("$dir/$f")) $r[] = $f; sort($r); return $r; }
function load_php($path, $var) { $sid = "\x01SID\x01"; $$var = null; include $path; return $$var; }
function load_ser($s, $repair) {
    if ($s === '') return '';
    $a = @unserialize($s);
    if ($a === false && $repair) $a = @unserialize(preg_replace_callback('/s:\d+:"(.*?)";/s', function ($m) { return 's:' . strlen($m[1]) . ':"' . $m[1] . '";'; }, $s));
    return $a;
}

foreach (['items', 'items1', 'npc', 'speak', 'desc', 'l_f', 'l_i'] as $dir) {
    $a = names("$orig/$dir"); $b = names("$built/$dir");
    foreach (array_diff($a, $b) as $f) $fail[] = "$dir/$f: missing in build";
    foreach (array_diff($b, $a) as $f) $fail[] = "$dir/$f: not in original";
    foreach (array_intersect($a, $b) as $f) {
        $checked++;
        $o = file_get_contents("$orig/$dir/$f"); $n = file_get_contents("$built/$dir/$f");
        if ($dir === 'items' || $dir === 'items1') {
            if (strpos($o, '<?') === false) $o = preg_replace('/\r?\n$/', '', $o);
            if ($o !== $n) $fail[] = "$dir/$f: differs";
        } elseif ($dir === 'npc' || $dir === 'speak') {
            if ($o === $n) continue;
            $var = $dir === 'npc' ? 'npc' : 'dialog';
            if (load_php("$orig/$dir/$f", $var) !== load_php("$built/$dir/$f", $var)) $fail[] = "$dir/$f: array differs";
        } elseif ($dir === 'l_i') {
            $x = load_ser($o, true); $y = load_ser($n, false);
            if ($y === false) { $fail[] = "$dir/$f: built file does not unserialize"; continue; }
            if (is_array($x)) {
                foreach (array_keys($x['i'] ?? []) as $k) if (is_string($k) && strncmp($k, 'u.', 2) === 0) unset($x['i'][$k]);
                ksort($x); if (is_array($y)) ksort($y);
            }
            if ($x !== $y) $fail[] = "$dir/$f: state differs";
            if (file_get_contents("$built/l_t/$f") !== $n) $fail[] = "l_t/$f: differs from built l_i";
        } elseif ($o !== $n) {
            $fail[] = "$dir/$f: differs";
        }
    }
}
echo "verify: $checked files checked, " . count($fail) . " differences\n";
foreach (array_slice($fail, 0, 40) as $f) echo "  $f\n";
exit($fail ? 1 : 0);
