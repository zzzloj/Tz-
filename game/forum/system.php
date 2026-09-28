<?
	
    $quota_t='10'; //лимит тем на страницу
	$quota_p='10'; //лимит постов на страницу в темах
	$tonline='300'; //через сколько секунд удальть из онлайн
    $apass='ZeRo7'; //пароль админа
    $title='Форум';
	$head='Форум : ';//верхушка страниц
	$poyas='2';//часовой пояс
	$errorlog='Ошибка авторизации!<br/>';
	$errorban='Вас забанили!<br/>';
	$errorsec='Разделы не определены. Обратитесь к администратору.';
	$errortem='Темы в данном разделе отсутствуют!';
	$errort='Ошибка создания темы! Возможно тема с таким названием уже существует в базе, или неправильно определен раздел для добавления темы!';
	$errormsg='Вы не ввели сообщение!';
	$errorp='Ответов в данной теме нет!';
	$errorpost='Такое сообщение уже есть в базе!';
	$errorclose='Данная тема закрыта для добавления сообщений!';
	$exitlog="<br/><a href=\"../1/f_newserver.php?login=$login&amp;p=$pass\">в игру</a></p></card></wml>"; //низ страниц
	$atitle='АДМИНКА ФОРУМА';
	$ahead='Админ-Панель<br/>';
		             function del($text)
           {
             $text=str_replace("&","&amp;", $text);
             $text=str_replace("$","\$", $text); //
             $text=str_replace(">","&gt;", $text);
             $text=str_replace("<","&lt;", $text);
             $text=str_replace("~","&tilde;", $text);
             $text=str_replace("`","&acute;", $text);
             $text=str_replace('"',"&quot;", $text);
             $text=str_replace("'","&quot;", $text);
             $text=str_replace("\n","<br/>", $text);
             $text=str_replace("\r","<br/>", $text);
             return $text;
           }
$ru = array("Ё","ё","©","®","™","€",
  "А","Б","В","Г","Д","Е","Ж","З","И","Й","К","Л","М","Н","О","П",
  "Р","С","Т","У","Ф","Х","Ц","Ч","Ш","Щ","Ъ","Ы","Ь","Э","Ю","Я",
  "а","б","в","г","д","е","ж","з","и","й","к","л","м","н","о","п",
  "р","с","т","у","ф","х","ц","ч","ш","щ","ъ","ы","ь","э","ю","я");

$ru_tr= array('Ё', 'ё', 'Ж', 'ж', 'Ч', 'ч', 'Щ', 'щ', 'Ю', 'ю', 'Я', 'я',
  'А', 'а', 'Б', 'б', 'В', 'в', 'Г', 'г', 'Д', 'д', 'Е', 'е',
  'З', 'з', 'И', 'и', 'Й', 'й', 'К', 'к', 'Л', 'л', 'М', 'м',
  'Н', 'н', 'О', 'о',  'П', 'п', 'Р', 'р', 'С', 'с', 'Т', 'т',
  'У', 'у', 'Ф', 'ф', 'Х', 'х', 'Ц', 'ц', 'Ш', 'ш', 'ъ', 'Ы', 'ы', 'ь', 'Э', 'э');

$en_tr= array('Jo', 'jo', 'Zh', 'zh', 'Ch', 'ch', 'Sh', 'sh', 'Ju', 'ju', 'Ja', 'ja', 
	'A', 'a', 'B', 'b', 'V', 'v', 'G', 'g', 'D', 'd', 'E', 'e',
	'Z', 'z', 'I', 'i', 'J', 'j', 'K', 'k', 'L', 'l', 'M', 'm',
	'N', 'n', 'O', 'o', 'P', 'p', 'R', 'r', 'S', 's', 'T', 't',
	'U', 'u', 'F', 'f', 'H', 'h', 'C', 'c', 'W', 'w', Chr(34), 'Y', 'y', Chr(39), 'X', 'x');

 $ru_utf8= array("Ё"=>"РЃ","ё"=>"С‘","©"=>"В©","®"=>"В®","™"=>"в„ў","€"=>"в‚¬", 
       "А"=>"A","Б"=>"Р‘","В"=>"B","Г"=>"Р“","Д"=>"Р”","Е"=>"E","Ж"=>"Р–","З"=>"3","И"=>"Р","Й"=>"Р™","К"=>"Рљ","Л"=>"Р›","М"=>"M","Н"=>"H","О"=>"O","П"=>"Рџ",
       "Р"=>"P","С"=>"C","Т"=>"T","У"=>"РЈ","Ф"=>"Р¤","Х"=>"X","Ц"=>"Р¦","Ч"=>"Р§","Ш"=>"РЁ","Щ"=>"Р©","Ъ"=>"РЄ","Ы"=>"Р«","Ь"=>"b","Э"=>"Р­","Ю"=>"Р®","Я"=>"РЇ",
       "а"=>"a","б"=>"Р±","в"=>"РІ","г"=>"Рі","д"=>"Рґ","е"=>"e","ж"=>"Р¶","з"=>"Р·","и"=>"Рё","й"=>"Р№","к"=>"Рє","л"=>"Р»","м"=>"Рј","н"=>"РЅ","о"=>"o","п"=>"Рї",
       "р"=>"p","с"=>"c","т"=>"С‚","у"=>"y","ф"=>"С„","х"=>"x","ц"=>"С†","ч"=>"С‡","ш"=>"С€","щ"=>"С‰","ъ"=>"СЉ","ы"=>"С‹","ь"=>"СЊ","э"=>"СЌ","ю"=>"СЋ","я"=>"СЏ");

//       UTF-8   Win-1251
function utf8_to_win($str) {
global $ru, $ru_utf8;
 return str_replace($ru_utf8, $ru, $str);

}

// Win-1251   UTF-8
function win_to_utf8($str) {
global $ru, $ru_utf8;
return strtr($str, $ru_utf8);
}
/*///////*/
$sdvig=3600*$poyas;

//       Win-1251   ST
function win_to_st($str) {
global $ru;
 $ru_st= array("&#1025;","&#1105;", "&#169;","&#174;","&#8482;","&#8364;",
       "A","&#1041;","B","&#1043;","&#1044;","E","&#1046;","3","&#1048;","&#1049;","K","&#1051;","M","H","O","&#1055;",
       "P","C","T","&#1059;","&#1060;","X","&#1062;","&#1063;","&#1064;","&#1065;","&#1066;","&#1067;","&#1068;","&#1069;","&#1070;","&#1071;",
"a","&#1073;","&#1074;","&#1075;","&#1076;","e","&#1078;","&#1079;","&#1080;","&#1081;","k","&#1083;","&#1084;","&#1085;","o","&#1087;",
       "p","c","&#1090;","y","&#1092;","x","&#1094;","&#1095;","&#1096;","&#1097;","&#1098;","&#1099;","&#1100;","&#1101;","&#1102;","&#1103;");

  return str_replace($ru, $ru_st, $str);
}

function del_symb($str) { 
return preg_replace("/[^\\xA\\x20-\\x7FА-я\\xA9\\xAE\\x99\\x88\\x93\\x94\\x96]/", "", $str);
}

function trans ($str) {
global $ru_tr, $en_tr;
 return str_replace($en_tr, $ru_tr, $str);
}

function ru_to_trans ($str) {
global $ru_tr, $en_tr;
 return str_replace($ru_tr, $en_tr, $cyrstr);
}

function changes32 ($str)
{//10
 $m1= array(chr(0), chr(1), chr(2), chr(3), chr(4), chr(5), chr(6), chr(7), chr(8), chr(9),
       chr(11), chr(12), chr(13),
       chr(14), chr(15), chr(16), chr(17), chr(18), chr(19),
       chr(20), chr(21), chr(22), chr(23), chr(24), chr(25), chr(26), chr(27), chr(28), chr(29),
       chr(30), chr(31));
 $m2= array("", "", "", "", "", "", "", "", "", "   ",
       "", "", "",
       "", "", "", "", "", "",
       "", "", "", "", "", "", "", "", "", "",
       "", "");
  return str_replace($m1, $m2, $str);
}

function ChangeSt ($text)
{
             $text=str_replace("&","&amp;", $text);
             $text=str_replace("$","\$", $text); //
             $text=str_replace(">","&gt;", $text);
             $text=str_replace("<","&lt;", $text);
             $text=str_replace("~","&tilde;", $text);
             $text=str_replace("`","&acute;", $text);
             $text=str_replace('"',"&quot;", $text);
             $text=str_replace("\n","<br/>", $text);
             $text=str_replace("»","&raquo; ", $text);
             $text=str_replace("«","&laquo;", $text);
             $text=str_replace("—","-", $text);
             $text=str_replace("”","&quot;", $text);
             $text=str_replace("“","&quot;", $text);
             $text=str_replace("‘","&quot;", $text);
             $text=str_replace("’","&quot;", $text);
             $text=str_replace("·","&middot;", $text);
             $text=str_replace("'","&quot;", $text);
             return $text;
}
	function checkcode($text)
{
	global $ru_utf8, $ru;
	$cru=count($ru);
for ( $b=0; $b<$cru; $b++) 
       {
       if(strstr($text,$ru[$b])!== false){$utf="ok";}
       }
if($utf=="ok"){$text=$text;}
else{$text=strtr($text, $ru_utf8);}
return $text;
}

	?>