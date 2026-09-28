<?php
// возвращает -1 если есть $login в папке online
$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);

if ($login) if (file_exists("online/".$login)) die("yes"); else die("no");
if ($count) die(@implode("",(array)(@file("count.dat"))));
echo 1;
