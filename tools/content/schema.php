<?php
// Engine strings -> JSON structures, driven by schema.json. The inverse is in
// build.py; both must stay exact (verify.php checks the round trip).

$GLOBALS['SCHEMA'] = json_decode(file_get_contents(__DIR__ . '/schema.json'), true);
const SID_SENTINEL_PHP = "\x01SID\x01";
const SID_PLACEHOLDER = '{{sid}}';

function canon_int($s) { return is_string($s) && preg_match('/^(0|-?[1-9][0-9]{0,17})$/', $s) ? (int)$s : null; }

function typed($s, $type) {
    if ($type === 'int') { $i = canon_int($s); return $i === null ? $s : $i; }
    if ($type === 'ints' && preg_match('/^-?\d+(:-?\d+)*$/', $s)) {
        $r = [];
        foreach (explode(':', $s) as $p) { $i = canon_int($p); if ($i === null) return $s; $r[] = $i; }
        return $r;
    }
    if ($type === 'effects' && preg_match('/^-?\d+:-?\d+(,-?\d+:-?\d+)*$/', $s)) {
        $r = [];
        foreach (explode(',', $s) as $p) {
            [$a, $b] = explode(':', $p);
            $a = canon_int($a); $b = canon_int($b);
            if ($a === null || $b === null) return $s;
            $r[] = [$a, $b];
        }
        return $r;
    }
    return $s;
}

function fields_to_json($s, $fields) {
    $o = []; $extra = [];
    foreach (explode('|', $s) as $i => $p) {
        if ($i < count($fields)) $o[$fields[$i][0]] = typed($p, $fields[$i][1]);
        else $extra[] = $p;
    }
    if ($extra) $o['extra'] = $extra;
    return $o;
}

function item_kind($id) {
    foreach ($GLOBALS['SCHEMA']['item_kinds'] as $k) if (strncmp($id, $k['prefix'], strlen($k['prefix'])) === 0) return $k;
}

// Item files: one line "name|price|..."; a final line break is dropped.
function item_to_json($id, $text) {
    $k = item_kind($id);
    return ['id' => $id, 'kind' => $k['kind']] + fields_to_json(preg_replace('/\r?\n$/', '', $text), $k['fields']);
}

function list_to_json($s, $kind) {
    if ($s === '') return $s;
    $parts = explode('|', $s);
    $r = [];
    foreach ($parts as $p) {
        if ($kind === 'ids') {
            if ($p === '' || strpos($p, ':') !== false) return $s;
            $r[] = $p;
            continue;
        }
        $f = explode(':', $p);
        if ($kind === 'counted') {
            if (count($f) !== 2 || $f[0] === '' || canon_int($f[1]) === null) return $s;
            $r[] = ['id' => $f[0], 'count' => canon_int($f[1])];
        } else { // random loot: id:chance:min:max
            if (count($f) !== 4 || $f[0] === '') return $s;
            $n = array_map('canon_int', array_slice($f, 1));
            if (in_array(null, $n, true)) return $s;
            $r[] = ['id' => $f[0], 'chance' => $n[0], 'min' => $n[1], 'max' => $n[2]];
        }
    }
    return $r;
}

// NPC template or live NPC: char/war become named fields, item lists become
// lists; every other key is kept as it is.
function entity_to_json(array $e) {
    global $SCHEMA;
    $o = [];
    foreach ($e as $k => $v) {
        if (($k === 'char' || $k === 'war') && is_string($v)) $o[$k] = fields_to_json($v, $SCHEMA[$k]);
        elseif (isset($SCHEMA['entity_lists'][$k]) && is_string($v)) $o[$k] = list_to_json($v, $SCHEMA['entity_lists'][$k]);
        elseif (($k === 'char' || $k === 'war' || isset($SCHEMA['entity_lists'][$k])) && is_array($v)) $o[$k] = ['$php' => $v]; // not the usual string: keep as is, marked
        else $o[$k] = $v;
    }
    return $o;
}

// Dialog topic "text#label#goto#label#goto..." (see f_speak.dat).
function dialog_to_json(array $d) {
    $o = [];
    foreach ($d as $topic => $s) {
        if (!is_string($s)) throw new RuntimeException("dialog topic $topic is not a string");
        if (strpos($s, SID_PLACEHOLDER) !== false) throw new RuntimeException("placeholder clash in $topic");
        $s = str_replace(SID_SENTINEL_PHP, SID_PLACEHOLDER, $s);
        if (strpos($s, '#') === false) { $o[$topic] = $s; continue; }
        $p = explode('#', $s);
        $t = ['text' => array_shift($p), 'options' => []];
        for ($i = 0; $i < count($p); $i += 2) {
            $opt = ['label' => $p[$i]];
            if ($i + 1 < count($p)) $opt['goto'] = $p[$i + 1];
            $t['options'][] = $opt;
        }
        $o[$topic] = $t;
    }
    return $o;
}

// Location state: d = "name|zone|label|target|...", i = objects, t = timers.
function location_to_json(array $state) {
    $o = [];
    $order = array_keys($state);
    if ($order !== array_values(array_intersect(['d', 'i', 't'], $order))) $o['key_order'] = $order;
    foreach ($state as $k => $v) {
        if ($k === 'd') {
            if (!is_string($v)) throw new RuntimeException('d is not a string');
            $p = explode('|', $v);
            $o['name'] = $p[0];
            if (count($p) > 1) $o['zone'] = typed($p[1], 'int');
            $o['exits'] = [];
            for ($i = 2; $i < count($p); $i += 2) {
                $x = ['label' => $p[$i]];
                if ($i + 1 < count($p)) $x['target'] = $p[$i + 1];
                $o['exits'][] = $x;
            }
        } elseif ($k === 'i') {
            $o['objects'] = [];
            foreach ($v as $id => $x) $o['objects'][$id] = is_array($x) ? entity_to_json($x) : $x;
        } elseif ($k === 't') {
            $o['timers'] = $v;
        } else {
            $o['state_extra'][$k] = $v;
        }
    }
    return $o;
}
