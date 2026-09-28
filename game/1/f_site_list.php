<?php

	$arr=array();
	$dh = opendir('online/'); 
	while (($fname = readdir($dh))!== false) if ($fname!='.' && $fname!='..' && $fname!='1.htaccess' && $fname!='.htaccess') {
		$tmp=unserialize(implode('',(array)(file('online/'.$fname))));
		$arr[$fname]=1;
		}
	closedir($dh);
	
	// Второй мир (game/2) закрыт: один мир на время тестов, см. README.

$stmp="";

	$stmp.="<br/><a href=\"g.php\">Сервер 1 (".count((array)($arr))." из 100)</a>";
	$count=count((array)($arr));
	
	
$stmp="Сейчас в игре ".$count." человек:".$stmp;
$stmp.="
<br/>
";



msg($stmp,"(Server 1)");


function msg($s,$title="") {
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
<card title=\"$title\">";
echo "
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}