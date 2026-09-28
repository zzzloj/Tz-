<?php
// World-state integrity after a test run: every location file the game wrote
// and game.dat must unserialize, no temp files of atomic writes may remain.
// Files still identical to the image copy are skipped: 43 locations shipped
// broken in the original archive and are repaired at load time (calcser).
// usage: php integrity.php <data game dir> <image game dir>
[$data, $img] = [$argv[1], $argv[2]];
$bad = []; $n = 0;
foreach (['1', '2'] as $s) {
    foreach (glob("$data/$s/l_i/*") as $f) {
        $orig = "$img/$s/l_i/" . basename($f);
        $c = file_get_contents($f);
        if (is_file($orig) && file_get_contents($orig) === $c) continue;
        $n++;
        if ($c === '' || !is_array(@unserialize($c))) $bad[] = $f;
    }
    if (is_file("$data/$s/game.dat")) { $n++; if (!is_array(@unserialize(file_get_contents("$data/$s/game.dat")))) $bad[] = "$data/$s/game.dat"; }
    foreach (glob("$data/$s/online/*") as $f) { $n++; if (!preg_match('/^\S+\n\d+$/', file_get_contents($f))) $bad[] = $f; }
}
$tmp = [];
$it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($data, FilesystemIterator::SKIP_DOTS));
foreach ($it as $f) if (strpos($f->getFilename(), '.tmp.') !== false) $tmp[] = (string)$f;
echo "integrity: $n written files checked, " . count($bad) . " broken, " . count($tmp) . " leftover temp files\n";
foreach ($bad as $b) echo "BROKEN $b\n";
foreach ($tmp as $t) echo "TEMP $t\n";
exit($bad || $tmp ? 1 : 0);
