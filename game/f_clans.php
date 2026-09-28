<?php
$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);

$g_list=10;

if ($clan) {
	if (!file_exists("clans/".$clan)) msg("Клан $clan не существует");
	$tmp=unserialize(implode("",file("clans/".$clan)));
	if (!isset($tmp["g"])) msg("Ошибка при загрузке клана $clan, сообщите разработчику.");
	if ($info) {if ($tmp["i"]) msg($tmp["i"]."<br/><anchor>[Назад]<prev/></anchor>","Инфо о клане ".$clan); else msg("Нет описания");}
	$keys=array_keys($tmp["g"]);
	$stmp="Глава клана: ".substr($keys[0],2);
	if($tmp["i"]) $stmp.="<br/><a href=\"f_clans.php?clan=$clan&info=1\">Инфо о клане</a>";
	$stmp.="<br/>Состав:";

	if (count((array)($tmp["m"]))>0) {
	if (!$start) $start=0;
	$keys=array_keys($tmp["m"]);
	for($i=$start;$i<$start+$g_list && $i<count((array)($keys));$i++) {
			$stmp.="<br/>".substr($keys[$i],2);
			}
		if ($start) {$stmp.= "\n<br/><a href=\"f_clans.php?clan=$clan\">^ </a>";}
		if ($start+$g_list<count((array)($keys))) {if (!$start) $stmp.="\n<br/>"; $stmp.= "<a href=\"f_clans.php?clan=$clan&start=".($start+$g_list)."\">+ (".(count((array)($keys))+1-$start-$g_list).")</a>";}
		} else $stmp.="<br/>Больше никого в клане нет."; 

	msg($stmp,"Клан *".$clan."*",0,"none");
	}

	// все кланы

	$arr=array();
	$dh = opendir("clans"); 
	while (($fname = readdir($dh))!== false) if ($fname!="." && $fname!=".." && $fname!="1.htaccess" && $fname!=".htaccess") {
		$tmp=unserialize(implode("",file("clans/".$fname)));
		$arr[$fname]=count((array)($tmp["m"]))+1;
		}
	closedir($dh);

	if (count((array)($arr))>0) {
		$stmp="<p>Всего кланов: ".count((array)($arr));
		arsort($arr);
		if (!$start) $start=0;
		$keys=array_keys($arr);
		for($i=$start;$i<$start+$g_list && $i<count((array)($keys));$i++) {
			$stmp.="<br/><a href=\"f_clans.php?clan=".$keys[$i]."\">".$keys[$i]."</a> (".$arr[$keys[$i]]." чел.)";
			}
		if ($start) {$stmp.= "\n<br/><a href=\"f_clans.php?\">^ </a>";}
		if ($start+$g_list<count((array)($keys))) {if (!$start) $stmp.="\n<br/>"; $stmp.= "<a href=\"f_clans.php?start=".($start+$g_list)."\">+ (".(count((array)($keys))+1-$start-$g_list).")</a>";}
		} else $stmp="<p>Нет ни одного клана"; 

	msg($stmp,"Список кланов",0,'none');

function msg($s,$tirle_z="Амулет Дракона",$journal=0,$menu="") {
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
<card tirle_z=\"$tirle_z\">";
echo "
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}
