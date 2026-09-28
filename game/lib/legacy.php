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
function legacy_fclose($h) {
    if (is_resource($h) && isset($GLOBALS['__legacy_atomic'][(int)$h])) return legacy_atomic_commit($h);
    return is_resource($h) ? fclose($h) : false;
}
function legacy_flock($h, $op, &$wb = null) { return is_resource($h) ? flock($h, (int)$op, $wb) : false; }
function legacy_feof($h) { return is_resource($h) ? feof($h) : true; } // true: loops end
function legacy_fseek($h, $off, $wh = SEEK_SET) { return is_resource($h) ? fseek($h, (int)$off, (int)$wh) : -1; }
function legacy_rewind($h) { return is_resource($h) ? rewind($h) : false; }
function legacy_ftell($h) { return is_resource($h) ? ftell($h) : false; }
function legacy_ftruncate($h, $size) { return is_resource($h) ? ftruncate($h, (int)$size) : false; }
function legacy_fflush($h) { return is_resource($h) ? fflush($h) : false; }

// Atomic file replacement. The engine rewrites world files in place
// (fopen "w" truncates, then fputs), so a process killed mid-request left an
// empty or cut location/game.dat ("Нет данных", lost items). Files opened for
// writing go to a temporary file next to the target and replace it with
// rename() on fclose (or at the end of the request), so readers and later
// requests see either the old or the new version, never a partial one.
function legacy_abs_path($path) {
    $path = (string)$path;
    if ($path === '' || $path[0] === '/') return $path;
    return getcwd() . '/' . $path;
}
function legacy_fopen_w($path, $mode = 'w') {
    $target = legacy_abs_path($path);
    $tmp = $target . '.tmp.' . getmypid() . '.' . mt_rand(100000, 999999);
    $h = @fopen($tmp, $mode);
    if ($h === false) return false;
    $GLOBALS['__legacy_atomic'][(int)$h] = array($h, $tmp, $target);
    return $h;
}
function legacy_atomic_commit($h) {
    $id = (int)$h;
    if (!isset($GLOBALS['__legacy_atomic'][$id])) return null;
    list(, $tmp, $target) = $GLOBALS['__legacy_atomic'][$id];
    unset($GLOBALS['__legacy_atomic'][$id]);
    $ok = is_resource($h) ? fclose($h) : true;
    if (!@rename($tmp, $target)) { @unlink($tmp); return false; }
    return $ok;
}
function legacy_atomic_write($path, $data) {
    $h = legacy_fopen_w($path, 'w');
    if ($h === false) return false;
    $n = fwrite($h, (string)$data);
    return legacy_atomic_commit($h) !== false ? $n : false;
}
register_shutdown_function(function () {
    if (empty($GLOBALS['__legacy_atomic'])) return;
    foreach ($GLOBALS['__legacy_atomic'] as $entry) legacy_atomic_commit($entry[0]);
});

}

require_once __DIR__ . '/mysql_compat.php';
