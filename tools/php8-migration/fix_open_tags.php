<?php
// Replace short open tags (<?) with <?php in files that start with PHP code.
$changed = 0; $skipped = [];
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $src = file_get_contents($f);
  if (!preg_match('/^(\xEF\xBB\xBF)?\s*<\?/', $src)) { $skipped[] = $f; continue; }
  $out = ''; $n = 0;
  foreach (token_get_all($src) as $t) {
    if (is_array($t) && $t[0] === T_OPEN_TAG && !preg_match('/^<\?php/i', $t[1])) {
      $rest = substr($t[1], 2);
      $t[1] = '<?php' . ($rest === '' ? ' ' : $rest); $n++;
    }
    $out .= is_array($t) ? $t[1] : $t;
  }
  if ($n) { file_put_contents($f, $out); $changed++; }
}
echo "changed $changed files; skipped (no leading tag): ", implode(' ', $skipped), "\n";
