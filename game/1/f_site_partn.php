<?php

$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);

if (!$id) $stmp="<p align=\"center\">
Ваше имя:
<br/><input name=\"nn\" emptyok=\"true\" type=\"text\" maxlength=\"10\" size=\"10\"/>
<br/>Пароль:
<br/><input name=\"pass\" emptyok=\"true\" type=\"password\" value=\"\" maxlength=\"10\" size=\"10\"/>
<br/><a href=\"f_site_partn.php?pi=$pi&id=login&amp;nn=$(nn:escape)&amp;pass=$(pass:escape)\">Войти</a>
<br/><a href=\"/magmail/gamereg.php?log=new&amp;nn=$(nn:escape)&amp;pass=$(pass:escape)&amp;pi=$pi\">Регистрация</a>
<br/><a href=\"f_site_partn.php?pi=$pi&id=about\">Помощь</a>
<br/><a href=\"g.php?pi=$pi\">Домой</a>
";

if ($id=="about") $stmp="<p>После регистрации в нашей партнерской программе вы получите персональную ссылку на Амулет Дракона, которую можете разместить на вашем WAP сайте. Любой, кто зарегистрируется в игре по вашей ссылке и оплатит аккаунт, принесет вам 25% от суммы платежа (без учета налогов и комиссии операторов за SMS сервис). Причем это распространяется на все дальнейшие платежи по продлению аккаунта.
<br/><a href=\"f_site_partn.php?pi=$pi&id=about2\">Подробнее</a>";

if ($id=="about2") $stmp="<p>Вы можете выбрать текстовую или графическую ссылку и размещать ее на любом количестве ваших сайтов или WAP страниц, а также рекламировать игру, чтобы привлечь максимальное число игроков и тем самым увеличить свою прибыль.
<br/>Расчет будет производиться раз в месяц через WEBMONEY (www.webmoney.ru)
<br/><a href=\"f_site_partn.php?pi=$pi&id=reghelp\">Как стать участником партнерской программы</a>";


if ($id=="reghelp") $stmp="<p>Если вы зарегистрированы в игре, то можете использовать данные игрового аккаунта для входа, иначе введите любой логин и пароль и выберите ссылку Регистрация. После входа в свой аккаунт вы можете указать номер WMZ кошелька, куда раз в месяц будет переводиться полученная вами прибыль, а также посмотреть статистику платежей и перевода средств.
<br/><a href=\"f_site_partn.php?pi=$pi\">В начало</a>";

if ($id=="bhelp") $stmp="<p>Ваша реальная прибыль составляет 15% от размера каждого платежа (примерно 40% берут операторы за сервис SMS и от оставшейся суммы, составляющей доход разработчиков игры, вам перечисляется 25%).";

if ($id=="lhelp") $stmp="<p>Вы можете разместить у себя текстовую или графическую ссылку на любом количестве ваших сайтов или wap страниц. Учтите, что оплачиваются не переходы по ссылке и не сам факт регистрации в игре по вашей ссылке, а только случаи реальной оплаты или продления игроком своего аккаунта.";

if ($id=="login") {			// вход в акк
	require_once("datafunc.php");
	if (!$pass) msg("Не указан пароль");
	$res=GetDataPartner($nn,$pass,$data);
	if ($res && $res!="Данные не найдены") msg($res);
	if ($res=="Данные не найдены") {
		// сохр. в список партнеров
		$partn=implode("",(array)(file("../partn.dat")));
		if (!$partn) msg("Ошибка регистрации нового партнера, сообщите об этом администрации");
		$partn.="|".$nn;
		$file = legacy_fopen_w ("../partn.dat", "w");
		if ($file===false) msg("Ошибка записи, попробуйте еще раз");
		legacy_fputs($file,$partn);
		legacy_fclose($file);
		$data=array("pi"=>"", "wm"=>"","email"=>"","time"=>time(), "reg"=>0, "stat"=>"","t1"=>0,"t2"=>0,"t3"=>0);
		$res=SetDataPartner($nn,$pass,serialize($data));
		if ($res) msg($res);
		msg("Регистрация завершена. Разместите на вашем сайте ссылку (код будет дальше). С каждого игрока, кто зарегистрируется по ней, вам будет переводиться 25% от прибыли с его платежей.<br/><a href=\"f_site_partn.php?id=login&nn=$nn&pass=$pass\">Продолжить</a>");
		}
	if (!$data) msg("Отсутствуют данные, сообщите логин $nn администрации.");
	$data=unserialize($data);
	$stmp="<p>Регистраций по вашей ссылке: ".intval($data["reg"]);
	$stmp.="<br/>***<br/>Платежи за текущий период:
	<br/>SMS 0.99$$: ".intval($data["t1"])."<br/>SMS 1.99$$: ".intval($data["t2"]);
	$stmp.="<br/>***<br/><a href=\"f_site_partn.php?id=bhelp\">Ваш баланс</a>: ".((intval($data["t1"])*0.99+intval($data["t2"])*1.99)*0.15)."$$";

	$stmp.="<br/><br/><a href=\"#opt\">Настройки</a>
	<br/><a href=\"f_site_partn.php?&id=link&amp;nn=$nn\">Код ссылки</a>
	<br/><a href=\"f_site_partn.php?&id=stat&amp;nn=$nn&pass=$pass\">Статистика</a>
	<br/><a href=\"f_site_partn.php?&id=about\">Помощь</a>
	<br/><a href=\"g.php\">Выход</a>
	";
	// карта для ввода wm и email
	$stmp.="</p></card><card id=\"opt\" title_v=\"Настройки\">
	<onevent type=\"onenterforward\"><refresh><setvar name=\"wm\" value=\"".$data["wm"]."\"/><setvar name=\"email\" value=\"".$data["email"]."\"/></refresh></onevent>
	<p>Ваш WMZ кошелек (12 цифр): 
	<br/><input name=\"wm\" emptyok=\"true\" format=\"*N\" maxlength=\"12\"/>
	<br/>Email для связи:
	<br/><input name=\"email\" emptyok=\"true\" type=\"text\" maxlength=\"30\"/>
	<br/><a href=\"f_site_partn.php?&id=opt&amp;nn=$nn&pass=$pass&wm=$(wm)&email=$(email)\">Сохранить</a>
	<br/><a href=\"f_site_partn.php?&id=login&amp;nn=$nn&pass=$pass\">Назад</a>
	";
	msg($stmp);
	}

if ($id=="stat") {			// статистика $nn, $pass
	require_once("datafunc.php");
	if (!$pass) msg("Не указан пароль");
	$res=GetDataPartner($nn,$pass,$data);
	if ($res) msg($res);
	if (!$data) msg("Отсутствуют данные, сообщите логин $nn администрации.");
	$data=unserialize($data);
	if (!$data["stat"]) msg("Вам пока не было ни одного перевода.");
	$stat=explode(":",$data["stat"]);
	$stmp="<p>Вам было переведено:";
	$sum=0;
	for($i=0;$i<count((array)($stat));$i= legacy_num($i) + legacy_num(2)) {$stmp.="<br/><b>".date("d/m Y H:i",legacy_num($stat[$i]))."</b>: ".$stat[legacy_num($i)+1]."$$"; $sum= legacy_num($sum) + legacy_num($stat[legacy_num($i)+1]);} 
	$stmp.="<br/>***<br/>Итого: ".$sum."$$";
	msg($stmp);
	}

if ($id=="opt") {				// сохранение настроек	$wm, $email
	require_once("datafunc.php");
	if (!$pass) msg("Не указан пароль");
	$res=GetDataPartner($nn,$pass,$data);
	if ($res) msg($res);
	if (!$data) msg("Отсутствуют данные, сообщите логин $nn администрации.");
	$data=unserialize($data);
	if ($wm) $wm=substr($wm,0,12);
	if ($email) $email=substr($email,0,30);
	$data["wm"]=$wm;
	$data["email"]=$email;
		$res=SetDataPartner($nn,$pass,serialize($data));
		if ($res) msg($res);
		msg("Настройки сохранены.<br/><a href=\"f_site_partn.php?id=login&nn=$nn&pass=$pass\">Продолжить</a>");
	}


if ($id=="link") {			// ссылка текстовая и графическая
	$stmp="<p>Ваш ID: <b>".$nn."</b><br/><a href=\"f_site_partn.php?id=lhelp\">Правила размещения</a>
	<br/>1. Текстовая ссылка:<br/><a href=\"http://wap.computermag.ru/game/1/g.php?pi=$nn\">Амулет Дракона</a>
	<br/>WML код:<br/>&lt;a href=\"http://wap.computermag.ru/game/1/g.php?pi=$nn\"&gt;Амулет Дракона&lt;/a&gt;
	<br/>1. Графическая ссылка:<br/><a href=\"http://wap.computermag.ru/game/1/g.php?pi=$nn\"><img alt=\"Амулет Дракона\" src=\"http://wap.computermag.ru/game/banner.wbmp\"/></a>
	<br/>WML код:<br/>&lt;a href=\"http://wap.computermag.ru/game/1/g.php?pi=$nn\"&gt;&lt;img alt=\"Амулет Дракона\" src=\"http://wap.computermag.ru/game/banner.wbmp\"/&gt;&lt;/a&gt;";
	}

msg($stmp."<br/><br/><anchor>[Назад]<prev/></anchor>");

function msg($s) {
	header("Content-type:text/vnd.wap.wml;charset=utf-8"); 

	setlocale (LC_CTYPE, 'ru_RU.CP1251'); 
	function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(legacy_num(ord($s)+848)); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");} 
	function translate($s) {return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s));} 

	ob_start("translate");
	$s=str_replace("&amp;","&",$s);
	$s=str_replace("&","&amp;",$s);
	$s=str_replace("&amp;lt;","&lt;",$s);
	$s=str_replace("&amp;gt;","&gt;",$s);
	$s=str_replace("&amp;quot;","&quot;",$s);
	if (substr($s,0,2)!="<p") $s="<p>".$s;
	echo "<?xml version=\"1.0\"?>\n<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
	echo "
<wml>
<card title_v=\"Амулет Дракона\">";
echo "
$s
</p>
</card>
</wml>";
	ob_end_flush();
	die("");
	}

function GetDataPartner($login,$pass,&$data) {
	$data=serialize(array("pi"=>"", "wm"=>"123456789012","email"=>"partn@mail.ru","reg"=>25,"time"=>time(),"stat"=>(time()-6180000).":4.67:".(time()-100000).":21.12","t1"=>3,"t2"=>2));
	//$data=serialize(array("pi"=>""));
	//return "Данные не найдены";
	return "";
	}

function SetDataPartner($login,$pass,$data) {
	return "";
	}
