<?php
// Wrap arithmetic operands in legacy_num() and operands of loose comparisons
// with numeric literals in legacy_cmp(), restoring PHP 5/7 numeric semantics
// on PHP 8. Only atomic operands (variables with [..]/->/:: chains, calls,
// string literals) are wrapped, so operator precedence is never affected.
//
// usage: php fix_numeric.php <file-list> | php fix_numeric.php --code '<?php ...'

const NUMERIC_FUNCS = ['intval', 'floatval', 'round', 'floor', 'ceil', 'abs', 'rand', 'mt_rand', 'count', 'sizeof',
    'strlen', 'time', 'ord', 'sqrt', 'pow', 'array_sum', 'mktime', 'legacy_num', 'legacy_cmp', 'filesize',
    'strpos', 'strrpos', 'substr_count', 'intdiv', 'fmod', 'crc32', 'is_numeric', 'isset', 'empty'];
// internal functions whose numeric parameters throw on non-numeric strings in PHP 8
const NUM_ARGS = ['round' => [1], 'floor' => [1], 'ceil' => [1], 'abs' => [1], 'rand' => [1, 2], 'mt_rand' => [1, 2],
    'sqrt' => [1], 'pow' => [1, 2], 'chr' => [1], 'dechex' => [1], 'number_format' => [1], 'str_repeat' => [2],
    'substr' => [2, 3], 'date' => [2], 'mktime' => [1, 2, 3, 4, 5, 6], 'array_fill' => [1, 2], 'sleep' => [1],
    'usleep' => [1], 'str_pad' => [2], 'range' => [1, 2], 'intdiv' => [1, 2], 'fmod' => [1, 2], 'max' => '*', 'min' => '*'];

function sig_tokens(array $toks): array {
    $sig = [];
    foreach ($toks as $i => $t) {
        if (is_array($t) && in_array($t[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true)) continue;
        $sig[] = $i;
    }
    return $sig;
}
function tid($t) { return is_array($t) ? $t[0] : $t; }
function ttext($t) { return is_array($t) ? $t[1] : $t; }

function transform(string $src, array &$stats): string {
    $toks = token_get_all($src);
    $S = sig_tokens($toks);
    $n = count($S);
    $T = function ($k) use ($toks, $S, $n) { return ($k >= 0 && $k < $n) ? $toks[$S[$k]] : null; };
    $id = function ($k) use ($T) { $t = $T($k); return $t === null ? null : tid($t); };
    $tx = function ($k) use ($T) { $t = $T($k); return $t === null ? null : ttext($t); };

    // bracket matching on significant tokens
    $match = []; $stack = []; $inStr = false; $strOpen = null;
    for ($k = 0; $k < $n; $k++) {
        $t = $id($k);
        if ($t === '"') { if (!$inStr) { $inStr = true; $strOpen = $k; } else { $inStr = false; $match[$k] = $strOpen; $match[$strOpen] = $k; } continue; }
        if ($t === T_START_HEREDOC) { $strOpen = $k; continue; }
        if ($t === T_END_HEREDOC) { $match[$k] = $strOpen; $match[$strOpen] = $k; continue; }
        if (in_array($t, ['(', '[', '{', T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES], true)) { $stack[] = $k; continue; }
        if (in_array($t, [')', ']', '}'], true)) { $o = array_pop($stack); if ($o !== null) { $match[$k] = $o; $match[$o] = $k; } }
    }

    $before = []; $after = []; $replace = [];
    $wrap = function ($a, $b, $fn) use (&$before, &$after, $S) {
        $before[$S[$a]] = ($before[$S[$a]] ?? '') . $fn . '(';
        $after[$S[$b]] = ')' . ($after[$S[$b]] ?? '');
    };

    // operand chain to the right starting at $k: returns [start, end] or null (skip)
    $right = function ($k) use ($id, $tx, $match, $n) {
        $s = $k;
        if ($id($k) === '@') $k++;
        $t = $id($k);
        if ($t === null) return null;
        if (in_array($t, [T_LNUMBER, T_DNUMBER, T_INT_CAST, T_DOUBLE_CAST, T_BOOL_CAST, T_ISSET, T_EMPTY, T_INC, T_DEC,
                          '-', '+', '!', '~', '(', '[', T_ARRAY, T_NEW, T_CLONE, T_LIST, T_STRING_CAST, T_ARRAY_CAST], true)) return null;
        if ($t === T_CONSTANT_ENCAPSED_STRING) return [$s, $k];
        if ($t === '"' || $t === T_START_HEREDOC) return isset($match[$k]) ? [$s, $match[$k]] : null;
        if ($t === '$' && $id($k + 1) === T_VARIABLE) { $k++; $t = T_VARIABLE; }
        if ($t === T_STRING || $t === T_NAME_FULLY_QUALIFIED) {
            if ($id($k + 1) === '(') {
                if (in_array(strtolower($tx($k)), NUMERIC_FUNCS, true)) return null;
                $k = $match[$k + 1] ?? null; if ($k === null) return null;
            } elseif ($id($k + 1) === T_DOUBLE_COLON) {
                // Class::CONST or Class::$prop or Class::method()
            } else {
                $low = strtolower($tx($k));
                if (in_array($low, ['true', 'false', 'null'], true)) return null;
            }
        } elseif ($t !== T_VARIABLE) return null;
        // postfix chain
        while (true) {
            $nx = $id($k + 1);
            if ($nx === '[' || ($nx === '{' && $id($k) === T_VARIABLE && false)) { $k = $match[$k + 1] ?? null; if ($k === null) return null; continue; }
            if ($nx === T_OBJECT_OPERATOR || $nx === T_NULLSAFE_OBJECT_OPERATOR || $nx === T_DOUBLE_COLON) {
                $k += 2; if (!in_array($id($k), [T_STRING, T_VARIABLE, T_CLASS], true)) return null; continue;
            }
            if ($nx === '(') { $k = $match[$k + 1] ?? null; if ($k === null) return null; continue; }
            break;
        }
        return [$s, $k];
    };

    // operand chain to the left ending at $e
    $left = function ($e) use ($id, $tx, $match) {
        $t = $id($e);
        if ($t === null) return null;
        if (in_array($t, [T_LNUMBER, T_DNUMBER, T_INC, T_DEC, T_END_HEREDOC], true)) return null;
        if ($t === T_CONSTANT_ENCAPSED_STRING) $start = $e;
        elseif ($t === '"') { $start = $match[$e] ?? null; if ($start === null) return null; }
        else {
            $p = $e; $start = null;
            while (true) {
                $c = $id($p);
                if ($c === ']') { $p = ($match[$p] ?? -1) - 1; if ($p < 0) return null; continue; }
                if ($c === ')') {
                    $o = $match[$p] ?? null; if ($o === null) return null;
                    $pc = $id($o - 1);
                    if (in_array($pc, [T_STRING, T_VARIABLE, T_NAME_FULLY_QUALIFIED, ']', ')'], true)) {
                        if ($pc === T_STRING && in_array(strtolower($tx($o - 1)), NUMERIC_FUNCS, true)) return null;
                        if (in_array($pc, [T_ARRAY, T_ISSET, T_EMPTY, T_LIST], true)) return null;
                        $p = $o - 1; continue;
                    }
                    return null; // parenthesised group: result is usually numeric already
                }
                if ($c === T_VARIABLE) {
                    $start = $p;
                    if ($id($p - 1) === '$') $start = $p - 1;
                    if (in_array($id($start - 1), [T_OBJECT_OPERATOR, T_NULLSAFE_OBJECT_OPERATOR, T_DOUBLE_COLON], true)) { $p = $start - 2; continue; }
                    break;
                }
                if ($c === T_STRING || $c === T_CLASS || $c === T_NAME_FULLY_QUALIFIED) {
                    if (in_array($id($p - 1), [T_OBJECT_OPERATOR, T_NULLSAFE_OBJECT_OPERATOR, T_DOUBLE_COLON], true)) { $p = $p - 2; continue; }
                    $low = strtolower($tx($p));
                    if (in_array($low, ['true', 'false', 'null'], true)) return null;
                    $start = $p; break;
                }
                return null;
            }
        }
        $pre = $id($start - 1);
        if (in_array($pre, ['!', '~', '&', T_INT_CAST, T_DOUBLE_CAST, T_BOOL_CAST, T_STRING_CAST, T_ARRAY_CAST, T_INC, T_DEC, T_NEW, T_CLONE, T_INSTANCEOF], true)) return null;
        if ($pre === '@') $start--;
        return [$start, $e];
    };

    $operandEnd = [T_VARIABLE, ']', ')', T_LNUMBER, T_DNUMBER, T_CONSTANT_ENCAPSED_STRING, '"', T_END_HEREDOC, T_STRING, T_INC, T_DEC];
    $cmpOps = [T_IS_EQUAL, T_IS_NOT_EQUAL, T_IS_SMALLER_OR_EQUAL, T_IS_GREATER_OR_EQUAL, '<', '>'];
    $leftBoundary = ['(', ',', '=', '?', ':', '[', ';', '{', '}', T_BOOLEAN_AND, T_BOOLEAN_OR, T_LOGICAL_AND, T_LOGICAL_OR, T_LOGICAL_XOR,
                     T_RETURN, T_ECHO, T_PRINT, T_OPEN_TAG, T_DOUBLE_ARROW, T_IF, T_ELSEIF, T_WHILE, T_CASE, '!', T_COALESCE,
                     T_PLUS_EQUAL, T_MINUS_EQUAL, T_CONCAT_EQUAL, T_MUL_EQUAL, T_DIV_EQUAL];
    $rightBoundary = [')', ',', ';', '?', ':', ']', '}', T_BOOLEAN_AND, T_BOOLEAN_OR, T_LOGICAL_AND, T_LOGICAL_OR, T_LOGICAL_XOR, T_CLOSE_TAG, T_COALESCE, T_DOUBLE_ARROW];
    $isNumLit = function ($k) use ($id) {
        if (in_array($id($k), [T_LNUMBER, T_DNUMBER], true)) return [$k, $k];
        if ($id($k) === '-' && in_array($id($k + 1), [T_LNUMBER, T_DNUMBER], true)) return [$k, $k + 1];
        return null;
    };
    $isNumLitLeft = function ($k) use ($id) {
        if (in_array($id($k), [T_LNUMBER, T_DNUMBER], true)) return ($id($k - 1) === '-') ? [$k - 1, $k] : [$k, $k];
        return null;
    };
    $inFor = false;
    for ($k = 0; $k < $n; $k++) {
        $t = $id($k);
        $isArith = in_array($t, ['+', '-', '*', '/', '%', T_POW], true);
        if ($isArith) {
            $binary = in_array($id($k - 1), $operandEnd, true)
                && !($id($k - 1) === T_STRING && in_array(strtolower($tx($k - 1)), ['return', 'case', 'echo', 'print'], true));
            if ($binary) {
                $l = $left($k - 1);
                if ($l) { $wrap($l[0], $l[1], 'legacy_num'); $stats['arith']++; }
            }
            $r = $right($k + 1);
            if ($r) { $wrap($r[0], $r[1], 'legacy_num'); $stats['arith']++; }
            continue;
        }
        if (in_array($t, [T_PLUS_EQUAL, T_MINUS_EQUAL, T_MUL_EQUAL, T_DIV_EQUAL, T_MOD_EQUAL, T_POW_EQUAL], true)) {
            $l = $left($k - 1);
            if (!$l || $id($l[0]) === '@' || $id($l[0]) === T_CONSTANT_ENCAPSED_STRING) continue;
            // LHS must not have side effects (no calls)
            $hasCall = false; for ($q = $l[0]; $q <= $l[1]; $q++) if ($id($q) === '(' || $id($q) === T_INC || $id($q) === T_DEC) $hasCall = true;
            if ($hasCall) continue;
            // RHS extent
            $depth = 0; $q = $k + 1;
            for (; $q < $n; $q++) {
                $c = $id($q);
                if (in_array($c, ['(', '[', '{', T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES], true)) { $depth++; continue; }
                if (in_array($c, [')', ']', '}'], true)) { if ($depth === 0) break; $depth--; continue; }
                if ($depth === 0 && in_array($c, [';', ',', T_CLOSE_TAG, T_AS, T_DOUBLE_ARROW], true)) break;
            }
            $rEnd = $q - 1; if ($rEnd <= $k) continue;
            $lhs = '';
            for ($q = $S[$l[0]]; $q <= $S[$l[1]]; $q++) $lhs .= ttext($toks[$q]);
            $op = substr(ttext($T($k)), 0, -1);
            $replace[$S[$k]] = '= legacy_num(' . $lhs . ') ' . $op . ' legacy_num(';
            $after[$S[$rEnd]] = ')' . ($after[$S[$rEnd]] ?? '');
            $stats['compound']++;
            continue;
        }
        if (in_array($t, $cmpOps, true)) {
            $lit = $isNumLit($k + 1);
            if ($lit && in_array($id($lit[1] + 1), $rightBoundary, true)) {
                $l = $left($k - 1);
                if ($l && in_array($id($l[0] - 1), $leftBoundary, true)) { $wrap($l[0], $l[1], 'legacy_cmp'); $stats['cmp']++; }
                continue;
            }
            $lit = $isNumLitLeft($k - 1);
            if ($lit && in_array($id($lit[0] - 1), $leftBoundary, true)) {
                $r = $right($k + 1);
                if ($r && in_array($id($r[1] + 1), $rightBoundary, true)) { $wrap($r[0], $r[1], 'legacy_cmp'); $stats['cmp']++; }
            }
            continue;
        }
        // numeric parameters of internal functions
        if ($t === T_STRING && $id($k + 1) === '(' && isset(NUM_ARGS[strtolower($tx($k))])
            && !in_array($id($k - 1), [T_OBJECT_OPERATOR, T_DOUBLE_COLON, T_FUNCTION, T_NEW, T_NULLSAFE_OBJECT_OPERATOR], true)) {
            $want = NUM_ARGS[strtolower($tx($k))];
            $close = $match[$k + 1] ?? null; if ($close === null) continue;
            $argStart = $k + 2; $depth = 0; $ai = 1;
            for ($q = $k + 2; $q <= $close; $q++) {
                $c = $id($q);
                if ($q === $close || ($depth === 0 && $c === ',')) {
                    $a = $argStart; $b = $q - 1;
                    if ($b >= $a && ($want === '*' || in_array($ai, $want, true))) {
                        $single = ($a === $b) ? $id($a) : null;
                        if (!in_array($single, [T_LNUMBER, T_DNUMBER], true)
                            && !($id($a) === T_STRING && $id($a + 1) === '(' && in_array(strtolower($tx($a)), NUMERIC_FUNCS, true) && ($match[$a + 1] ?? -1) === $b)
                            && !($id($a) === T_INT_CAST || $id($a) === T_DOUBLE_CAST)) {
                            if (strtolower($tx($k)) === 'max' || strtolower($tx($k)) === 'min') {
                                // max/min accept mixed; only wrap when comparing against numbers
                            } else { $wrap($a, $b, 'legacy_num'); $stats['args']++; }
                        }
                    }
                    $ai++; $argStart = $q + 1; continue;
                }
                if (in_array($c, ['(', '[', '{', T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES], true)) $depth++;
                elseif (in_array($c, [')', ']', '}'], true)) $depth--;
            }
        }
    }
    $out = '';
    foreach ($toks as $i => $t) $out .= ($before[$i] ?? '') . ($replace[$i] ?? ttext($t)) . ($after[$i] ?? '');
    return $out;
}

$stats = ['arith' => 0, 'compound' => 0, 'cmp' => 0, 'args' => 0];
if (($argv[1] ?? '') === '--code') { echo transform($argv[2], $stats), "\n"; fwrite(STDERR, json_encode($stats) . "\n"); exit; }
$files = 0;
foreach (file($argv[1], FILE_IGNORE_NEW_LINES) as $f) {
    $src = file_get_contents($f);
    if (strpos($src, 'legacy_num(') !== false || strpos($src, 'legacy_cmp(') !== false) continue; // already done
    $out = transform($src, $stats);
    if ($out !== $src) { file_put_contents($f, $out); $files++; }
}
echo json_encode($stats), " in $files files\n";
