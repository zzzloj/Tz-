<?php
// Runs the old engine's f_calcparam.dat for each case from stdin (JSON) and
// prints war and char as UTF-8 JSON. Used by CalcparamParityTest.
// usage: php calcparam.php <game/lib/legacy.php> <built game/1> <game/1/f_calcparam.dat>
error_reporting(E_ALL & ~E_WARNING & ~E_NOTICE & ~E_DEPRECATED);
require realpath($argv[1]);
$GLOBALS['calcFile'] = realpath($argv[3]);
chdir($argv[2]);
$cases = json_decode(file_get_contents('php://stdin'), true);

function calc($c) {
    global $l_i, $game;
    $l_i = array(); $game = array();
    $loc = 'x'; $login = 'u.t';
    $char = array('t', '20', '20', '20', '20', '', '', '', '', '', '', '', $c['mounted'] ? '1:10' : '');
    $items = array();
    foreach ($c['equip'] as $i) $items[] = $i . ':1';
    $l_i[$loc][$login] = array('char' => implode('|', $char), 'skills' => implode('|', $c['skills']),
        'items' => implode('|', $items), 'equip' => implode('|', $c['equip']), 'war' => '');
    if ((include $GLOBALS['calcFile']) === false || $l_i[$loc][$login]['war'] === '') {
        fwrite(STDERR, "f_calcparam.dat did not run\n");
        exit(1);
    }
    return array('war' => iconv('CP1251', 'UTF-8', $l_i[$loc][$login]['war']),
                 'char' => iconv('CP1251', 'UTF-8', $l_i[$loc][$login]['char']));
}

$out = array();
foreach ($cases as $c) $out[] = calc($c);
echo json_encode($out, JSON_UNESCAPED_UNICODE);
