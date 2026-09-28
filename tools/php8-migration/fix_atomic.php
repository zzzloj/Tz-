<?php
// fopen(PATH, "w"|"w+"|"wb") -> legacy_fopen_w(PATH, mode): atomic replace on fclose.
$total = 0; $files = 0; $skip = ['game.dat']; // game.dat is locked and written by g.php itself
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $src = file_get_contents($f);
  if (stripos($src, 'fopen') === false) continue;
  $toks = token_get_all($src); $n = count($toks); $k = 0;
  for ($i = 0; $i < $n; $i++) {
    if (!is_array($toks[$i]) || $toks[$i][0] !== T_STRING || strtolower($toks[$i][1]) !== 'fopen') continue;
    $j = $i + 1; while ($j < $n && is_array($toks[$j]) && $toks[$j][0] === T_WHITESPACE) $j++;
    if (($toks[$j] ?? null) !== '(') continue;
    // collect top-level args
    $depth = 0; $args = [[]]; $e = $j + 1;
    for (; $e < $n; $e++) {
      $s = is_array($toks[$e]) ? $toks[$e][1] : $toks[$e];
      if (in_array($s, ['(', '[', '{'])) $depth++;
      elseif (in_array($s, [')', ']', '}'])) { if ($depth === 0) break; $depth--; }
      elseif ($s === ',' && $depth === 0) { $args[] = []; continue; }
      $args[count($args) - 1][] = $e;
    }
    if (count($args) !== 2) continue;
    $modeTok = null; foreach ($args[1] as $ti) if (!(is_array($toks[$ti]) && $toks[$ti][0] === T_WHITESPACE)) $modeTok = $ti;
    if ($modeTok === null || !is_array($toks[$modeTok]) || $toks[$modeTok][0] !== T_CONSTANT_ENCAPSED_STRING) continue;
    $mode = substr($toks[$modeTok][1], 1, -1);
    if (!in_array($mode, ['w', 'w+', 'wb', 'w+b'], true)) continue;
    $pathSrc = ''; foreach ($args[0] as $ti) $pathSrc .= is_array($toks[$ti]) ? $toks[$ti][1] : $toks[$ti];
    if (preg_match('/[\'"]game\.dat[\'"]/', $pathSrc)) continue;
    $toks[$i][1] = 'legacy_fopen_w'; $k++;
  }
  if ($k) { $out = ''; foreach ($toks as $t) $out .= is_array($t) ? $t[1] : $t; file_put_contents($f, $out); $total += $k; $files++; echo "$f: $k\n"; }
}
fwrite(STDERR, "converted $total fopen calls in $files files\n");
