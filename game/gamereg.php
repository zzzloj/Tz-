<?php
  extract($HTTP_GET_VARS);
  extract($HTTP_POST_VARS);
  extract($HTTP_COOKIE_VARS);
  extract($HTTP_SESSION_VARS);
session_name("SID");
session_start();
if(empty($_SESSION[press_kod])){
$press_kod=rand(1000,9999);

session_register("press_kod");}
msg("<p>Проверочный код: * <b>".$press_kod."</b>
<br/>Введите проверочный код:
<br/><input  format=\"*N\" maxlength=\"9\" name=\"chis\"/>
<br/><a href=\"gamereg2.php?log=new&amp;nn=$(nn)&amp;pass=$(pass)&amp;pi=$(pi)&amp;chis=$(chis)&amp;" . SID . "\">[далее]</a>");
function msg($s) {
	header("Content-type:text/vnd.wap.wml;charset=utf-8");

	setlocale (LC_CTYPE, 'ru_RU.CP1251');
	function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(ord($s)+848); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");}
	function translate($s) {return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s));}

	ob_start("translate");
	echo "<?xml version=\"1.0\"?>\n<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
	echo "
<wml>
<card title=\"[регистрация]\">";
echo "
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}
