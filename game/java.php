<?php

msg("<p>WapBrouser сжимает WAP страницы в 2..3 раза и работает через gprs-internet (20 центов на 1Мб траффика, а стандартный wap-gprs от 1 до 3 долларов за 1Мб).
<br/>Должен работать на любых телефонах с J2ME, т.к. использует только стандартные средства.
<br/><a href=\"WapBrouser.jad\">Скачать WapBrouser 1.0 (12кб)</a>
<br/><anchor>[Назад]<prev/></anchor>");
function msg($s) {
	header("Content-type:text/vnd.wap.wml;charset=utf-8"); 

	setlocale (LC_CTYPE, 'ru_RU.CP1251'); 
	function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(legacy_num(ord($s)+848)); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");} 
	function translate($s) {return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s));} 

	ob_start("translate");
	echo "<?xml version=\"1.0\"?>\n<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
	echo "
<wml>
<card tirle_z=\"WapBrouser 0.1\">";
echo "
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}
