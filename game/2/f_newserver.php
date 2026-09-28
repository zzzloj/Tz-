<?php

$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);

	if (!$login) msg("Не указан логин, введите его на предыдущем экране");
	if (!$p) msg("Не указан пароль, введите его на предыдущем экране");

	$login=substr($login,0,15);
	$p=substr($p,0,15);
	if (substr($login,0,2)!="u.") $login="u.".$login;
	$login=strtolower($login);
	if (file_exists("online/".$login)) msg("Ваш персонаж сейчас в игре, подождите 10 минут или зайдите и сохранитесь вручную, чтобы персонаж покинул игру.");

	$srv=@implode("",@file("server.dat"));
	$tmp=@file("servers.dat");
	if (!$newsrv) {	// выведем список серверов
		$stmp="Выберите сервер:";
		for($i=1;$i<count((array)($tmp));$i++){
			$stmp.="<br/><a href=\"".trim($tmp[$i])."f_connect.php?login=$login&p=$p\">Сервер $i</a>";
			}
		msg($stmp);
		}

	// проверка
	if ($newsrv<1 || $newsrv>count((array)($tmp))-1) msg("Неверный номер сервера","Ошибка");	


function calcser($s) {return "s:".strlen($s).":\"".$s."\";";}
function msg($s,$title_v="Территория зла") {
		header ("Expires: Thu, 01 Jan 1970 00:00:01 GMT");
		header ("Last-Modified: " . gmdate("D, d M Y H:i:s") . " GMT");
		header ("Cache-Control: no-cache, no-store, must-revalidate, max-age=0");
		header ("Pragma: no-cache");
		header("Content-type:text/vnd.wap.wml;charset=utf-8"); 
	setlocale (LC_CTYPE, 'ru_RU.CP1251'); 
	function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(ord($s)+848); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");} 
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
