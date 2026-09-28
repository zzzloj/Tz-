<?php
// Wrap array parameters of by-value array functions in (array)(...):
// PHP 8 throws TypeError on null/false/strings where PHP 5/7 only warned.
$spec = [ // function => argument positions (1-based), '*' = all
  'implode' => [2], 'join' => [2], 'in_array' => [2], 'array_search' => [2], 'array_key_exists' => [2],
  'array_keys' => [1], 'array_values' => [1], 'array_flip' => [1], 'array_unique' => [1], 'array_sum' => [1],
  'array_slice' => [1], 'array_reverse' => [1], 'array_merge' => '*', 'array_diff' => '*', 'array_intersect' => '*',
  'array_count_values' => [1], 'array_fill_keys' => [1], 'array_combine' => [1, 2], 'array_filter' => [1],
];
$total = 0; $files = 0;
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $src = file_get_contents($f);
  if (!preg_match('/\b(' . implode('|', array_keys($spec)) . ')\s*\(/i', $src)) continue;
  $toks = token_get_all($src); $n = count($toks); $out = ''; $k = 0;
  for ($i = 0; $i < $n; $i++) {
    $t = $toks[$i];
    $out .= is_array($t) ? $t[1] : $t;
    if (!is_array($t) || $t[0] !== T_STRING) continue;
    $fn = strtolower($t[1]); if (!isset($spec[$fn])) continue;
    $p = $i - 1; while ($p >= 0 && is_array($toks[$p]) && in_array($toks[$p][0], [T_WHITESPACE, T_COMMENT])) $p--;
    if ($p >= 0 && is_array($toks[$p]) && in_array($toks[$p][0], [T_OBJECT_OPERATOR, T_DOUBLE_COLON, T_FUNCTION, T_NEW, T_NULLSAFE_OBJECT_OPERATOR])) continue;
    $j = $i + 1; $ws = '';
    while ($j < $n && is_array($toks[$j]) && $toks[$j][0] === T_WHITESPACE) { $ws .= $toks[$j][1]; $j++; }
    if ($j >= $n || $toks[$j] !== '(') continue;
    // split arguments at top level
    $args = ['']; $depth = 0; $e = $j + 1;
    for (; $e < $n; $e++) {
      $s = is_array($toks[$e]) ? $toks[$e][1] : $toks[$e];
      if (in_array($s, ['(', '[', '{']) || (is_array($toks[$e]) && in_array($toks[$e][0], [T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES]))) $depth++;
      elseif (in_array($s, [')', ']', '}'])) { if ($depth === 0) break; $depth--; }
      elseif ($s === ',' && $depth === 0) { $args[] = ''; continue; }
      $args[count($args) - 1] .= $s;
    }
    $want = $spec[$fn];
    if (in_array($fn, ['implode', 'join']) && count($args) === 1) $want = [1];
    $changed = false;
    foreach ($args as $ai => $a) {
      if (!($want === '*' || in_array($ai + 1, $want))) continue;
      if (trim($a) === '' || preg_match('/^\s*(\(array\)|array\s*\(|\[)/i', $a)) continue;
      if ($fn === 'implode' && count($args) === 2 && $ai === 1 && preg_match('/^\s*[\'"]/', $a)) continue; // reversed legacy order
      $lead = preg_match('/^\s*/', $a, $m) ? $m[0] : '';
      $args[$ai] = $lead . '(array)(' . substr($a, strlen($lead)) . ')'; $changed = true;
    }
    if (!$changed) continue;
    // rebuild: we already appended the function name; append ws, '(' and args, ')'
    $out .= $ws . '(' . implode(',', $args) . ')';
    $i = $e; $k++;
  }
  if ($k) { file_put_contents($f, $out); $total += $k; $files++; }
}
echo "wrapped $total calls in $files files\n";
