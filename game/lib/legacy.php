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

// File functions on a failed fopen(): PHP 5/7 warned and returned false/null,
// PHP 8 throws TypeError. The engine often reads optional files unchecked.
function legacy_fread($h, $len) { return (is_resource($h) && (int)$len > 0) ? fread($h, (int)$len) : false; }
function legacy_fwrite($h, $data, $len = null) { return is_resource($h) ? ($len === null ? fwrite($h, (string)$data) : fwrite($h, (string)$data, (int)$len)) : false; }
function legacy_fputs($h, $data, $len = null) { return legacy_fwrite($h, $data, $len); }
function legacy_fgets($h, $len = null) { return is_resource($h) ? ($len === null ? fgets($h) : fgets($h, (int)$len)) : false; }
function legacy_fgetc($h) { return is_resource($h) ? fgetc($h) : false; }
function legacy_fclose($h) { return is_resource($h) ? fclose($h) : false; }
function legacy_flock($h, $op, &$wb = null) { return is_resource($h) ? flock($h, (int)$op, $wb) : false; }
function legacy_feof($h) { return is_resource($h) ? feof($h) : true; } // true: loops end
function legacy_fseek($h, $off, $wh = SEEK_SET) { return is_resource($h) ? fseek($h, (int)$off, (int)$wh) : -1; }
function legacy_rewind($h) { return is_resource($h) ? rewind($h) : false; }
function legacy_ftell($h) { return is_resource($h) ? ftell($h) : false; }
function legacy_ftruncate($h, $size) { return is_resource($h) ? ftruncate($h, (int)$size) : false; }
function legacy_fflush($h) { return is_resource($h) ? fflush($h) : false; }

}

require_once __DIR__ . '/mysql_compat.php';
