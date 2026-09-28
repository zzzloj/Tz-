<?php
// Two-phase handling of PHP code that lives inside data:
//   extract <root> <workdir>  -> writes <workdir>/NNNN.php + map.json
//   apply   <root> <workdir>  -> puts transformed code back
// Covers "eval: CODE" segments ('#'-separated) inside string literals of
// speak/npc/plugin files, and whole files that are eval()'d without a tag.
$mode = $argv[1]; $root = rtrim($argv[2], '/'); $work = rtrim($argv[3], '/');
$wholeFiles = ['1/f_attackf.dat', '2/f_attackf.dat'];
function lit_value($lit) {
  if ($lit[0] === "'") return str_replace(["\\\\", "\\'"], ["\\", "'"], substr($lit, 1, -1));
  return null; // only single-quoted literals are handled
}
function lit_encode($v) { return "'" . str_replace(["\\", "'"], ["\\\\", "\\'"], $v) . "'"; }
$files = [];
foreach (['1', '2'] as $srv) foreach (['speak', 'npc', 'plugin'] as $d)
  foreach (glob("$root/$srv/$d/*") as $f) if (is_file($f) && strpos(file_get_contents($f), 'eval: ') !== false) $files[] = $f;
if ($mode === 'extract') {
  @mkdir($work, 0777, true); $map = []; $n = 0; $skipped = 0;
  foreach ($wholeFiles as $wf) {
    $id = sprintf('%04d', $n++); file_put_contents("$work/$id.php", "<?php\n" . file_get_contents("$root/$wf"));
    $map[$id] = ['whole' => $wf];
  }
  foreach ($files as $f) {
    foreach (token_get_all(file_get_contents($f)) as $ti => $t) {
      if (!is_array($t) || $t[0] !== T_CONSTANT_ENCAPSED_STRING || strpos($t[1], 'eval: ') === false) continue;
      $v = lit_value($t[1]); if ($v === null) { $skipped++; continue; }
      foreach (explode('#', $v) as $si => $seg) {
        if (strncmp($seg, 'eval: ', 6) !== 0) continue;
        $id = sprintf('%04d', $n++);
        file_put_contents("$work/$id.php", "<?php\n" . substr($seg, 6));
        $map[$id] = ['file' => $f, 'tok' => $ti, 'seg' => $si];
      }
    }
  }
  file_put_contents("$work/map.json", json_encode($map, JSON_INVALID_UTF8_SUBSTITUTE));
  echo "extracted $n fragments, skipped $skipped non-single-quoted literals\n";
} else {
  $map = json_decode(file_get_contents("$work/map.json"), true);
  $byFile = [];
  foreach ($map as $id => $m) {
    $code = file_get_contents("$work/$id.php");
    if (strncmp($code, "<?php\n", 6) !== 0) die("lost prefix in $id\n");
    $code = substr($code, 6);
    if (isset($m['whole'])) { file_put_contents("$root/{$m['whole']}", $code); continue; }
    $byFile[$m['file']][$m['tok']][$m['seg']] = $code;
  }
  $changed = 0;
  foreach ($byFile as $f => $toks) {
    $all = token_get_all(file_get_contents($f)); $out = '';
    foreach ($all as $ti => $t) {
      if (isset($toks[$ti])) {
        $segs = explode('#', lit_value($t[1]));
        foreach ($toks[$ti] as $si => $code) $segs[$si] = 'eval: ' . $code;
        $t[1] = lit_encode(implode('#', $segs));
      }
      $out .= is_array($t) ? $t[1] : $t;
    }
    file_put_contents($f, $out); $changed++;
  }
  echo "applied to $changed files\n";
}
