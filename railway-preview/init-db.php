<?php
// The 2007 dump uses TYPE=MyISAM, rejected by current MySQL versions.
$host = getenv('MYSQLHOST');
$user = getenv('MYSQLUSER');
$pass = getenv('MYSQLPASSWORD');
$name = getenv('MYSQLDATABASE');
$port = getenv('MYSQLPORT') ? getenv('MYSQLPORT') : '3306';
if (!$host || !$user || !$name) {
    fwrite(STDERR, "MYSQLHOST, MYSQLUSER, MYSQLPASSWORD, MYSQLDATABASE are required\n");
    exit(1);
}
$db = false;
for ($i = 0; $i < 24; ++$i) {
    $db = @mysql_connect($host . ':' . $port, $user, $pass, true);
    if ($db) break;
    sleep(2);
}
if (!$db || !@mysql_select_db($name, $db)) {
    fwrite(STDERR, "Could not connect to the game database\n");
    exit(1);
}
$sql = "CREATE TABLE IF NOT EXISTS users (
    names VARCHAR(10), vals TEXT NOT NULL, pass VARCHAR(10),
    lastrefr VARCHAR(11), nick VARCHAR(10), gametime VARCHAR(11),
    status VARCHAR(2), sent TEXT NOT NULL, regtime VARCHAR(11),
    refrint TEXT NOT NULL, messlim TEXT NOT NULL, mode TEXT NOT NULL,
    email VARCHAR(40), pi VARCHAR(3), INDEX (nick)
) ENGINE=MyISAM DEFAULT CHARSET=cp1251";
if (!mysql_query($sql, $db)) {
    fwrite(STDERR, "Could not initialize users table: " . mysql_error($db) . "\n");
    exit(1);
}

// Give the preview operator a game account without putting the Basic Auth
// password into the old game's 10-character plaintext/URL password field.
$previewUser = getenv('PREVIEW_USER');
$previewPassword = getenv('PREVIEW_PASSWORD');
$databasePassword = getenv('MYSQLPASSWORD');
if ($previewUser && $previewPassword && $databasePassword) {
    $gamePassword = substr(hash_hmac('sha256', $previewPassword, $databasePassword), 0, 10);
    $escapedUser = mysql_real_escape_string($previewUser, $db);
    $escapedPassword = mysql_real_escape_string($gamePassword, $db);
    $existing = mysql_query("SELECT nick FROM users WHERE nick='$escapedUser' LIMIT 1", $db);
    if (!$existing) {
        fwrite(STDERR, "Could not check preview game account: " . mysql_error($db) . "\n");
        exit(1);
    }
    if (mysql_num_rows($existing) === 0) {
        $insert = "INSERT INTO users (nick,pass,names,vals,sent,refrint,messlim,mode) "
            . "VALUES ('$escapedUser','$escapedPassword','','','0','','','')";
        if (!mysql_query($insert, $db)) {
            fwrite(STDERR, "Could not create preview game account: " . mysql_error($db) . "\n");
            exit(1);
        }
    } else {
        if (!mysql_query("UPDATE users SET pass='$escapedPassword' WHERE nick='$escapedUser'", $db)) {
            fwrite(STDERR, "Could not update preview game account: " . mysql_error($db) . "\n");
            exit(1);
        }
    }
}
mysql_close($db);
