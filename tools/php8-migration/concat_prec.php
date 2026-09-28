<?php
// Find "A . B + C" / "A . B - C": PHP 8 evaluates it as A . (B + C), PHP 5/7 as (A . B) + C.
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $toks = token_get_all(file_get_contents($f)); $sig = [];
  foreach ($toks as $t) if (!(is_array($t) && in_array($t[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT]))) $sig[] = $t;
  $n = count($sig);
  for ($k = 1; $k < $n; $k++) {
    $s = is_array($sig[$k]) ? $sig[$k][1] : $sig[$k];
    if ($s !== '+' && $s !== '-') continue;
    // walk left over one operand: variable chain / literal / call
    $p = $k - 1; $depth = 0;
    for (; $p >= 0; $p--) {
      $c = is_array($sig[$p]) ? $sig[$p][0] : $sig[$p];
      if ($c === ']' || $c === ')') { $depth++; continue; }
      if ($c === '[' || $c === '(') { $depth--; if ($depth < 0) break; continue; }
      if ($depth > 0) continue;
      if (in_array($c, [T_VARIABLE, T_STRING, T_LNUMBER, T_DNUMBER, T_CONSTANT_ENCAPSED_STRING, T_OBJECT_OPERATOR, '$', '@'], true)) continue;
      break;
    }
    $c = is_array($sig[$p] ?? null) ? $sig[$p][0] : ($sig[$p] ?? null);
    if ($c === '.' ) {
      $line = is_array($sig[$k-1]) ? $sig[$k-1][2] : '?';
      for ($q = $k - 1; $q >= 0 && !is_array($sig[$q]); $q--); $line = $sig[$q][2] ?? '?';
      echo "$f:$line\n";
    }
  }
}
