<?php

$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);
////////////////
	if (!$login) { msg("Введите название клана на предыдущем экране и выберите ссылку \"Найти игрока\", чтобы узнать, находится ли он в игре и если да, то на каком сервере играет.<br/><anchor>Назад<prev/></anchor>"); }
	else {





	$clan=$login;
$clan=substr($tc[0],legacy_num(strpos($tc[0],"*")+1),legacy_num(strrpos($tc[0],"*")-strpos($tc[0],"*")-1));
	if ($clan && file_exists("clans/".$clan)) $tmp=unserialize(implode("",(array)(file("clans/".$clan)))); else $tmp=array();
	$keys=array_keys((array)($tmp["m"]));
			if ($keys) foreach ($keys as $i) if ($i && $i!=$login) if (file_exists("online/".$i) && filesize("online/".$i)!=1 && ($id==$i || $klan)) {
			$tmp=file("online/".$i);
			$tmp[0]=str_replace("\n","",$tmp[0]);
			$tmp[0]=str_replace("\r","",$tmp[0]);

			loadloc($tmp[0]);
						if (!isset($l_i[$tmp[0]][$i])) if ($klan) continue; else msg("В игре нет данных ".substr($i,2),"Ошибка");
////////////////
	if (time()>legacy_num($game["lastai"])+240) include "f_online.dat";	// обновим список
	$g_list=10;
	$stmp="";  if (!$start) $start=0; $i=-1;
	$dh = opendir("online/");
	while (($fname = readdir($dh))!== false) if ($fname!="." && $fname!=".." && $fname!="1.htaccess" && $fname!=".htaccess") {
		$i++;
		if($i>=$start && $i<legacy_num($start)+legacy_num($g_list)) {
			$tmp = file("online/".$fname);
			if ($tmp[0]==".") continue;
			for($it=0;legacy_cmp($it)<2;$it++) {$tmp[$it]=str_replace("\n","",$tmp[$it]);$tmp[$it]=str_replace("\r","",$tmp[$it]);}
			if (!$tmp[0]) {@unlink("online/".$fname); $i--; continue;}
			loadloc($tmp[0]);
			if (isset($l_i[$tmp[0]][$fname])) {
				$tmp1=explode("|",$l_i[$tmp[0]][$fname]["char"]);


				$tmp3=@file("online/$fname");
				$tmp4=explode("\n",$tmp3[0]);
$stmp.="\n<br/>-----<br/><b>".$tmp1[0]."</b>";
				} else {$i--;@unlink("online/".$fname);}
			}
		}
	closedir($dh);

	if (legacy_cmp($i)>-1) $stmp="<p>Сейчас в игре ".(legacy_num($i)+1)." (на этом сервере разрешено не более $g_max) игроков:".$stmp;
	if (legacy_cmp($i)>-1) {

		if ($start) {$stmp.= "\n<br/><a href=\"$PHP_SELF?sid=$sid&adm=online\">^ </a>";}
		if (legacy_num($start)+legacy_num($g_list)<legacy_num($i)+1) {if (!$start) $stmp.="\n<br/>"; $stmp.= "<a href=\"$PHP_SELF?sid=$sid&adm=online&start=".(legacy_num($start)+legacy_num($g_list))."\">+ (".(legacy_num($i)+1+1-legacy_num(legacy_num($start))-legacy_num($g_list)).")</a>";}
		} else $stmp="<p>Сейчас в игре никого нет";

	//$stmp.= "\n<br/><br/><a href=\"f_site_list.php\">Список серверов</a><br/><br/><anchor>[Назад]<prev/></anchor>";

	msg($stmp,"Онлайн (Cервер ".implode("",(array)(file("server.dat"))).")");
//////////////////////////////////////////////////////////////////////////////////////












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
