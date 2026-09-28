<?php

$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp);
$PHP_SELF="f_ip.php";

if($info) $stmp="Так как gprs работает медленнее, чем обычный интернет, то игроки с компьютера имеют в игре слишком большое преимущество по скорости, поэтому на сервера 1, 3 и 4 вход разрешен только с мобильных телефонов, а на сервер 2 и с телефонов, и с компьютерных вап-эмуляторов.<br/><anchor>Назад<prev/></anchor>";

else if($send) {
	$send=substr($send,0,1000);
	// UTF-8 русские буквы
	$send=str_replace("\xd0\x81","Ё",$send);
	$send=str_replace("\xd1\x91","ё",$send);
	$send=preg_replace_callback("/\xd0([\x90-\xbf])/",function ($m) { return chr(ord($m[1])+48); },$send);
	$send=preg_replace_callback("/\xd1([\x80-\x8f])/",function ($m) { return chr(ord($m[1])+112); },$send);
	$sid=explode(".",$sid);
	//@mail("blade@mag.su", $_SERVER["REMOTE_ADDR"], $sid[1]." ".$send);
	$stmp="Спасибо, ваши данные отправлены на blade@mag.su, через 24 часа в случае успешной проверки вам будет открыт доступ на все сервера, а пока временно можете играть на <a href=\"http://mags.com.ru/game/2/g.php\">Cервере 2</a>.<br/>Пожалуйста, при переписке указывайте ваш IP: ".$_SERVER["REMOTE_ADDR"];
	}

else $stmp="Ваш IP [".$_SERVER["REMOTE_ADDR"]."] не опознан, возможно, вы используете wap-эмулятор? В таком случае вам разрешен вход только на <a href=\"http://mags.com.ru/game/2/g.php\">Сервер 2</a> (<a href=\"$PHP_SELF?info=1\">почему?</a>).
<br/>Если же вы сейчас с телефона, то укажите название вашего оператора и адрес сайта с gprs настройками (или другую необходимую информацию):
<br/><input name=\"send\" emptyok=\"true\" type=\"text\" />
<br/><a href=\"$PHP_SELF?send=$(send)&sid=$sid\">Отправить</a>";

msg($stmp);


function msg($s) {
	header("Content-type:text/vnd.wap.wml;charset=utf-8"); 

	setlocale (LC_CTYPE, 'ru_RU.CP1251'); 
	function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(ord($s)+848); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");} 
	function translate($s) {return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s));} 
	$s=str_replace("&amp;","&",$s);
	$s=str_replace("&","&amp;",$s);
	$s=strtr($s,"КЕНХВАРОСМТехарос","KEHXBAPOCMTexapoc");
	ob_start("translate");
	echo "<?xml version=\"1.0\"?>\n<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
	echo "
<wml>
<card tirle_z=\"Амулет Дракона\">";
echo "<p>
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}
