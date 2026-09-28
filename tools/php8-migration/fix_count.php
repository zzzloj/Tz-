<?php
// count(X) / sizeof(X) -> count((array)(X)): PHP 8 throws on null/scalars,
// PHP 5/7 returned 0/1. For arrays the cast is a no-op.
$total = 0; $files = 0;
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $src = file_get_contents($f);
  if (!preg_match('/\b(count|sizeof)\s*\(/i', $src)) continue;
  $toks = token_get_all($src); $n = count($toks); $out = ''; $k = 0;
  for ($i = 0; $i < $n; $i++) {
    $t = $toks[$i];
    $out .= is_array($t) ? $t[1] : $t;
    if (!is_array($t) || $t[0] !== T_STRING || !in_array(strtolower($t[1]), ['count', 'sizeof'])) continue;
    // previous significant token must not be ->, ::, function, new
    $p = $i - 1; while ($p >= 0 && is_array($toks[$p]) && in_array($toks[$p][0], [T_WHITESPACE, T_COMMENT])) $p--;
    if ($p >= 0 && is_array($toks[$p]) && in_array($toks[$p][0], [T_OBJECT_OPERATOR, T_DOUBLE_COLON, T_FUNCTION, T_NEW, T_NULLSAFE_OBJECT_OPERATOR])) continue;
    $j = $i + 1; $ws = '';
    while ($j < $n && is_array($toks[$j]) && $toks[$j][0] === T_WHITESPACE) { $ws .= $toks[$j][1]; $j++; }
    if ($j >= $n || $toks[$j] !== '(') continue;
    // find end of first argument (top-level ',' or closing ')')
    $depth = 0; $arg = ''; $e = $j + 1;
    for (; $e < $n; $e++) {
      $s = is_array($toks[$e]) ? $toks[$e][1] : $toks[$e];
      if (in_array($s, ['(', '[', '{']) || (is_array($toks[$e]) && in_array($toks[$e][0], [T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES]))) $depth++;
      elseif (in_array($s, [')', ']', '}'])) { if ($depth === 0) break; $depth--; }
      elseif ($s === ',' && $depth === 0) break;
      $arg .= $s;
    }
    if (trim($arg) === '' || preg_match('/^\s*\(array\)/i', $arg)) continue;
    $out .= $ws . '((array)(' . $arg . ')';
    $i = $e - 1; $k++;
  }
  if ($k) { file_put_contents($f, $out); $total += $k; $files++; }
}
echo "wrapped $total calls in $files files\n";
