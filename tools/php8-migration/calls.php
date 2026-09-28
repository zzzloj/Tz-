<?php
// Print full source of preg_replace calls that use the /e modifier.
$files = file($argv[1], FILE_IGNORE_NEW_LINES);
$seen = [];
foreach ($files as $f) {
  $toks = @token_get_all(file_get_contents($f));
  $n = count($toks);
  for ($i = 0; $i < $n; $i++) {
    if (!is_array($toks[$i]) || $toks[$i][0] !== T_STRING || strtolower($toks[$i][1]) !== 'preg_replace') continue;
    $depth = 0; $src = ''; $j = $i + 1;
    for (; $j < $n; $j++) { $t = $toks[$j]; $s = is_array($t) ? $t[1] : $t; $src .= $s;
      if ($s === '(' ) $depth++; elseif ($s === ')') { $depth--; if ($depth === 0) break; } }
    if (!preg_match('/^\(\s*([\'"])(.)(.*?)\2([a-zA-Z]*)\1/s', $src, $m) || !str_contains($m[4], 'e')) continue;
    $key = preg_replace('/\s+/', ' ', $src);
    $seen[$key][] = "$f:" . $toks[$i][2];
  }
}
uasort($seen, fn($a,$b)=>count($b)<=>count($a));
foreach ($seen as $k => $locs) echo count($locs), "x  preg_replace", iconv('CP1251','UTF-8//IGNORE',$k), "\n     e.g. ", $locs[0], "\n";
