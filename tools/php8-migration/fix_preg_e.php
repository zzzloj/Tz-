<?php
// Convert preg_replace(.../e, "code", ...) to preg_replace_callback with a closure.
// Keeps PHP 5.6-compatible syntax so the same code runs on both versions.
function lit_value($lit) {
  if ($lit[0] === "'") return str_replace(["\\\\", "\\'"], ["\\", "'"], substr($lit, 1, -1));
  if (preg_match('/\$[A-Za-z_{]/', $lit)) throw new Exception("interpolating literal: $lit");
  return eval('return ' . $lit . ';');
}
function drop_e($lit) {
  $q = $lit[0]; $body = substr($lit, 1, -1);
  if (!preg_match('/^(.*[^a-zA-Z])([a-zA-Z]*)$/s', $body, $m) || strpos($m[2], 'e') === false) throw new Exception("no e: $lit");
  return $q . $m[1] . str_replace('e', '', $m[2]) . $q;
}
function closure_for($code) {
  $code = rtrim(trim($code), ';');
  // quoted whole backreference: '\1', "\1", "$1"  -> $m[1]
  $code = preg_replace('/([\'"])[\\\\$](\d)\1/', '$m[$2]', $code);
  // backreference inside a double-quoted string: "- \1 -" -> "- {$m[1]} -"
  $code = preg_replace_callback('/"(?:[^"\\\\]|\\\\.)*"/s', function ($s) {
    return preg_replace('/[\\\\$](\d)/', '{\$m[$1]}', $s[0]);
  }, $code);
  if (preg_match('/(?<![\\\\{])[\\\\](\d)/', $code)) throw new Exception("leftover backref: $code");
  preg_match_all('/\$([A-Za-z_]\w*)/', $code, $vm);
  $vars = array_values(array_diff(array_unique($vm[1]), ['m', 'GLOBALS', '_GET', '_POST', '_SERVER', '_SESSION', '_COOKIE']));
  $use = $vars ? ' use (&$' . implode(', &$', $vars) . ')' : '';
  return 'function ($m)' . $use . ' { return ' . $code . '; }';
}
$report = [];
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $toks = token_get_all(file_get_contents($f));
  $n = count($toks); $changed = 0;
  for ($i = 0; $i < $n; $i++) {
    if (!is_array($toks[$i]) || $toks[$i][0] !== T_STRING || strtolower($toks[$i][1]) !== 'preg_replace') continue;
    $p = $i + 1; while (is_array($toks[$p]) && $toks[$p][0] === T_WHITESPACE) $p++;
    if ($toks[$p] !== '(') continue;
    $a1 = $p + 1; while (is_array($toks[$a1]) && $toks[$a1][0] === T_WHITESPACE) $a1++;
    if (!is_array($toks[$a1]) || $toks[$a1][0] !== T_CONSTANT_ENCAPSED_STRING) continue;
    $pat = $toks[$a1][1];
    $val = lit_value($pat);
    if (!preg_match('/^(.).*\1([a-zA-Z]*)$/s', $val, $mm) || strpos($mm[2], 'e') === false) continue;
    $c = $a1 + 1; while (is_array($toks[$c]) && $toks[$c][0] === T_WHITESPACE) $c++;
    $a2 = $c + 1; while (is_array($toks[$a2]) && $toks[$a2][0] === T_WHITESPACE) $a2++;
    if ($toks[$c] !== ',' || !is_array($toks[$a2]) || $toks[$a2][0] !== T_CONSTANT_ENCAPSED_STRING) { $report[] = "MANUAL $f:{$toks[$i][2]}"; continue; }
    $code = lit_value($toks[$a2][1]);
    $toks[$a1][1] = drop_e($pat);
    if (trim($code) === '') { $changed++; continue; }   // empty replacement: /e is a no-op
    $toks[$i][1] = 'preg_replace_callback';
    $toks[$a2][1] = closure_for($code);
    $changed++;
  }
  if ($changed) {
    $out = ''; foreach ($toks as $t) $out .= is_array($t) ? $t[1] : $t;
    file_put_contents($f, $out); $report[] = "$f: $changed";
  }
}
echo implode("\n", $report), "\n";
