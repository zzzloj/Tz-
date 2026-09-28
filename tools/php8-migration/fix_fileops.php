<?php
// Rename file-handle calls to the null-safe legacy_* wrappers in lib/legacy.php.
$fns = ['fread','fwrite','fputs','fgets','fgetc','fclose','flock','feof','fseek','rewind','ftell','ftruncate','fflush'];
$total = 0; $files = 0;
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $src = file_get_contents($f);
  if (!preg_match('/\b(' . implode('|', $fns) . ')\s*\(/i', $src)) continue;
  $toks = token_get_all($src); $out = ''; $k = 0; $prev = null;
  foreach ($toks as $i => $t) {
    if (is_array($t) && $t[0] === T_STRING && in_array(strtolower($t[1]), $fns, true)) {
      $j = $i + 1; while (isset($toks[$j]) && is_array($toks[$j]) && $toks[$j][0] === T_WHITESPACE) $j++;
      $pid = is_array($prev) ? $prev[0] : $prev;
      if (($toks[$j] ?? null) === '(' && !in_array($pid, [T_OBJECT_OPERATOR, T_DOUBLE_COLON, T_FUNCTION, T_NEW, T_NULLSAFE_OBJECT_OPERATOR], true)) {
        $t[1] = 'legacy_' . strtolower($t[1]); $k++;
      }
    }
    $out .= is_array($t) ? $t[1] : $t;
    if (!(is_array($t) && in_array($t[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true))) $prev = $t;
  }
  if ($k) { file_put_contents($f, $out); $total += $k; $files++; }
}
echo "renamed $total calls in $files files\n";
