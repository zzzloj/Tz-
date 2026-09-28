<?php
// Rewrite removed curly-brace string offsets ($s{0}) to $s[0] using the lexer.
$changed = 0;
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
  $src = file_get_contents($f);
  $toks = token_get_all($src);
  $out = ''; $stack = []; $prevSig = null; $n = 0;
  foreach ($toks as $t) {
    $id = is_array($t) ? $t[0] : null; $s = is_array($t) ? $t[1] : $t;
    if ($id === T_CURLY_OPEN || $id === T_DOLLAR_OPEN_CURLY_BRACES) { $stack[] = 'b'; }
    elseif ($s === '{') {
      $pid = is_array($prevSig) ? $prevSig[0] : $prevSig;
      if ($pid === T_VARIABLE || $pid === ']' || $pid === '}o' ) { $stack[] = 'o'; $s = '['; $n++; }
      else $stack[] = 'b';
    } elseif ($s === '}') {
      $top = array_pop($stack);
      if ($top === 'o') { $s = ']'; $out .= $s; $prevSig = '}o'; continue; }
    }
    $out .= $s;
    if (!in_array($id, [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true)) $prevSig = ($s === ']' ) ? ']' : ($id !== null ? [$id] : $s);
    if (is_array($prevSig)) $prevSig = $prevSig[0];
  }
  if ($n) { file_put_contents($f, $out); $changed++; echo "$f: $n\n"; }
}
fwrite(STDERR, "files changed: $changed\n");
