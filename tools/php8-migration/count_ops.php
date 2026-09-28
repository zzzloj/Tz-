<?php
$ar = 0; $cmp = 0; $cmpLit = 0; $compound = 0;
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $toks = token_get_all(file_get_contents($f)); $sig = [];
  foreach ($toks as $t) if (!(is_array($t) && in_array($t[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT]))) $sig[] = $t;
  foreach ($sig as $i => $t) {
    $s = is_array($t) ? $t[1] : $t;
    if (in_array($s, ['+', '-', '*', '/', '%'])) $ar++;
    if (is_array($t) && in_array($t[0], [T_PLUS_EQUAL, T_MINUS_EQUAL, T_MUL_EQUAL, T_DIV_EQUAL, T_MOD_EQUAL])) $compound++;
    if (is_array($t) && in_array($t[0], [T_IS_EQUAL, T_IS_NOT_EQUAL, T_IS_SMALLER_OR_EQUAL, T_IS_GREATER_OR_EQUAL]) || in_array($s, ['<', '>'])) {
      $cmp++;
      $l = $sig[$i-1] ?? null; $r = $sig[$i+1] ?? null;
      if ((is_array($l) && in_array($l[0], [T_LNUMBER, T_DNUMBER])) || (is_array($r) && in_array($r[0], [T_LNUMBER, T_DNUMBER]))) $cmpLit++;
    }
  }
}
echo "arithmetic ops: $ar, compound assigns: $compound, loose comparisons: $cmp (with numeric literal: $cmpLit)\n";
