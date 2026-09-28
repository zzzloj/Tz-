<?php
// PHP 5/7 semantics the legacy engine relies on, for PHP 8.
//
// The game stores character data as "a|b|c" strings and does arithmetic on
// the pieces. PHP 8 throws TypeError for arithmetic on non-numeric strings
// ("" + 1) and changed loose comparison ("" == 0 is now false). The migration
// wrapped such operands in legacy_num()/legacy_cmp() mechanically; during the
// refactor they should become real types and these helpers go away.
if (!function_exists('legacy_num')) {

/** Value of $v as a PHP 5/7 arithmetic operand. */
function legacy_num($v) {
    if (is_int($v) || is_float($v)) return $v;
    if (is_string($v)) {
        if ($v === '') return 0;
        if (is_numeric($v)) return $v + 0;
        // PHP 5/7 used the leading numeric part ("12abc" -> 12), else 0.
        if (preg_match('/^\s*[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?/', $v, $m)) return $m[0] + 0;
        return 0;
    }
    if ($v === null || is_bool($v)) return (int)$v;
    return $v; // arrays (+ is union for arrays) and objects stay as they are
}

/** Operand for a loose comparison with a number, as in PHP 5/7. */
function legacy_cmp($v) {
    return is_string($v) ? legacy_num($v) : $v;
}

}

require_once __DIR__ . '/mysql_compat.php';
