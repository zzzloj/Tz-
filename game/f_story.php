<?php

$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];	// в зависимости от настроек сервера
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);									// теперь появятся переменные $url, $p и т.д.

srand ((float) microtime() * 10000000);

$stmp="";
if (!$id) $stmp="<p>
<br/><a href=\"f_story.php?pi=$pi&amp;id=about\">Введение</a> (<b>15/06 Blade</b>) 
<br/>***



<br/><a href=\"1/g.php?pi=$pi\">[На главную]</a> 
";



if ($id) {
$id=str_replace('/','',$id);
if (!file_exists("story/".$id.".htm")) msg("<p>Нет текста, сообщите название рассказа разработчику");
$stmp=implode("",file("story/".$id.".htm"));

$stmp=str_replace("|",":",$stmp);
$stmp=wordwrap($stmp,500,"|");
$stmp=explode("|",$stmp);

if (!$start || $start<0) $start=1;
if ($start>count((array)($stmp))) $start=count((array)($stmp));
$stmp[$start-1]="<p>Стр. ".$start." из ".count((array)($stmp))." [<a href=\"#page\">изм.</a>]<br/>".$stmp[$start-1];
if ($start<count((array)($stmp))) $stmp[$start-1].="<br/><a href=\"f_story.php?pi=$pi&amp;id=$id&amp;r=".chr(rand(97,122)).chr(rand(97,122))."&amp;start=".($start+1)."\">[след. стр.]</a>";
$stmp[$start-1].="<br/><br/><a href=\"f_story.php?pi=$pi\">[В начало]</a>";
$stmp[$start-1].="<br/></p></card><card id=\"page\" title=\"Перейти\">\n<p>Введите номер страницы:<br/><input name=\"start\"  format=\"*N\" value=\"1\" maxlength=\"3\"/><br/><a href=\"f_story.php?pi=$pi&amp;id=$id&amp;start=$(start)\">Перейти</a>";
} else {$start=1; $stmp=array($stmp);}

$stmp=$stmp[$start-1]."";

if ($pi) {

	$stmp.="";
	}

msg($stmp);

function msg($s) {
	header("Content-type:text/vnd.wap.wml;charset=utf-8"); 

	setlocale (LC_CTYPE, 'ru_RU.CP1251'); 
	function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(ord($s)+848); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");} 
	function translate($s) {return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s));} 

	ob_start("translate");
	echo "<?xml version=\"1.0\"?>\n<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
	echo "
<wml>
<card title=\"новости\">";
echo "
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}
