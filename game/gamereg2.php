<?php

  extract($_GET);
  extract($_POST);
  extract($_COOKIE);
  extract(isset($_SESSION) ? $_SESSION : array());
session_name("SID");
session_start();

	if($chis==$_SESSION['press_kod']){

require("datafunc.php");
$result=SetUser($_GET['nn'], "", $_GET['pass']);
if ($result) msg($result);
msg("Регистрация успешно завершена!<br/>
<a href=\"1/f_connect.php?login=".$_GET['nn']."&amp;p=".$_GET['pass']."\">[в игру]</a>
"); } else {

msg("Проверочное число не совпало!!!");}
$press_kod='';
$press_kod=rand(10000,99999); 
$_SESSION["press_kod"]=$press_kod;
session_unset();
session_destroy();

function msg($s) {
	header("Content-type:text/vnd.wap.wml;charset=utf-8");

	setlocale (LC_CTYPE, 'ru_RU.CP1251');
	function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(ord($s)+848); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");}
	function translate($s) {return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s));}

	ob_start("translate");
	echo "<?xml version=\"1.0\"?>\n<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
	echo "
<wml>
<card title=\"Регистрация\">
<p>";
echo "
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}
