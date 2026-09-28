<?php
// Static scan of legacy code for PHP 8 incompatibilities. Lexer only, so it
// also works on files that do not parse.
$files = file($argv[1], FILE_IGNORE_NEW_LINES);
$known = array_change_key_case(get_defined_constants(), CASE_LOWER);
$removed = array_flip(['ereg','eregi','ereg_replace','eregi_replace','split','spliti','sql_regcase','each','create_function',
 'session_register','session_unregister','session_is_registered','get_magic_quotes_gpc','get_magic_quotes_runtime','set_magic_quotes_runtime',
 'call_user_method','call_user_method_array','money_format','convert_cyr_string','hebrevc','restore_include_path','mysql_pconnect',
 'utf8_encode','utf8_decode','strftime','gmstrftime','mhash','mcrypt_encrypt','key_exists','fgetss','image2wbmp','png2wbmp','jpeg2wbmp']);
$userConst = [];
foreach ($files as $f) { if (preg_match_all('/define\s*\(\s*[\'"](\w+)[\'"]/i', file_get_contents($f), $m)) foreach ($m[1] as $c) $userConst[strtolower($c)] = 1; }
$out = [];
$add = function($kind, $f, $line, $detail) use (&$out) { $out[$kind][] = "$f:$line $detail"; };
foreach ($files as $f) {
  $toks = @token_get_all(file_get_contents($f));
  $sig = []; // significant tokens
  foreach ($toks as $t) { if (is_array($t) && in_array($t[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT])) continue; $sig[] = $t; }
  $n = count($sig);
  for ($i = 0; $i < $n; $i++) {
    $t = $sig[$i]; if (!is_array($t)) continue;
    [$id, $text, $line] = $t;
    $prev = $sig[$i-1] ?? null; $next = $sig[$i+1] ?? null;
    $pt = is_array($prev) ? $prev[0] : $prev; $nt = is_array($next) ? $next[0] : $next;
    if ($id === T_VARIABLE && preg_match('/^\$HTTP_\w+_VARS$/', $text)) $add('http_vars', $f, $line, $text);
    if ($id === T_STRING) {
      $lc = strtolower($text);
      $isCall = $nt === '(';
      $afterMember = in_array($pt, [T_OBJECT_OPERATOR, T_DOUBLE_COLON, T_NULLSAFE_OBJECT_OPERATOR, T_FUNCTION, T_NEW, T_CLASS, T_CONST, T_INSTANCEOF, T_EXTENDS, T_IMPLEMENTS, T_GOTO], true);
      if ($isCall && !$afterMember) {
        if (str_starts_with($lc, 'mysql_')) $add('mysql', $f, $line, $text);
        elseif (isset($removed[$lc])) $add('removed_fn', $f, $line, $text);
        elseif (in_array($lc, ['mktime','gmmktime']) && ($sig[$i+2] ?? null) === ')') $add('mktime_noargs', $f, $line, $text);
        elseif (in_array($lc, ['preg_replace','preg_replace_callback'])) {
          $arg = $sig[$i+2] ?? null;
          if (is_array($arg) && in_array($arg[0], [T_CONSTANT_ENCAPSED_STRING])) {
            $pat = substr($arg[1], 1, -1);
            if (preg_match('/^(.).*\1([a-zA-Z]*)$/s', $pat, $m) && str_contains($m[2], 'e')) $add('preg_e', $f, $line, substr($arg[1],0,60));
          } else $add('preg_dynamic', $f, $line, is_array($arg)? token_name($arg[0]).' '.substr($arg[1],0,40) : $arg);
        }
      } elseif (!$isCall && !$afterMember && $nt !== T_DOUBLE_COLON && $nt !== ':' && !in_array($pt, [T_AS, T_USE, T_NAMESPACE], true)
                && !isset($known[$lc]) && !isset($userConst[$lc]) && !in_array($lc, ['true','false','null','parent','self','static'])) {
        $add('bare_const', $f, $line, $text);
      }
    }
    if ($id === T_CURLY_OPEN) continue;
  }
}
foreach ($out as $k => $v) { echo "== $k: ", count($v), "\n"; }
file_put_contents($argv[2], json_encode($out, JSON_PRETTY_PRINT|JSON_INVALID_UTF8_SUBSTITUTE|JSON_UNESCAPED_UNICODE));
