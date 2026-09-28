<?php

$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);

	if (!$login) msg("Введите имя игрока на предыдущем экране и выберите ссылку \"Найти игрока\", чтобы узнать, находится ли он в игре и если да, то на каком сервере играет.<br/><anchor>Назад<prev/></anchor>");
	if (substr($login,0,2)!="u.") $login="u.".strtolower($login);

	// проверим на всех серверах
	$tmp=file("servers.dat");
	for($i=1;$i<count((array)($tmp));$i++) if (@implode("",(array)(@file(trim($tmp[$i])."f_site_common.php?login=".$login)))=="yes") {$loc=""; msg("В данный момент игрок с именем ".substr($login,2)." находится на <a href=\"".trim($tmp[$i])."g.php?r=".rand(1,99)."\">сервере ".$i."</a>.<br/><anchor>Назад<prev/></anchor>");}

	msg("Игрока с именем ".substr($login,2)." сейчас в игре нет.<br/><anchor>Назад<prev/></anchor>");


function msg($s,$title_v="Найти игрока") {
		header ("Expires: Thu, 01 Jan 1970 00:00:01 GMT");
		header ("Last-Modified: " . gmdate("D, d M Y H:i:s") . " GMT");
		header ("Cache-Control: no-cache, no-store, must-revalidate, max-age=0");
		header ("Pragma: no-cache");
		header("Content-type:text/vnd.wap.wml;charset=utf-8"); 
	setlocale (LC_CTYPE, 'ru_RU.CP1251'); 
	function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(legacy_num(ord($s)+848)); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");} 
	function translate($s) {return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s));} 
	ob_start("translate");
	$s=str_replace("&amp;","&",$s);
	$s=str_replace("&","&amp;",$s);
	if (substr($s,0,2)!="<p") $s="<p>".$s;
	echo "<?xml version=\"1.0\"?>\n<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
	echo "
<wml>
<card title=\"$title_v\">";
echo "
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}
