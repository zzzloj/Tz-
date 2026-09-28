<?php 
header("Cache-Control: no-cache");
require("config.ssp");
$ref=rand(10000,1000000); 
if(!isset($ps))
{
echo "<HTML>\n";
echo "<HEAD>\n";
echo "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=windows-1251\">\n";
echo "<TITLE>Добавить фото</TITLE>\n";
echo "<style TYPE=\"text/css\"><!--\n";
echo ".body{ text-align: center; font-family: Times New Roman; font-size: 12pt;}\n";
echo "A:Link{ Color: #FF4A4A; text-decoration: none;}\n";
echo "A:Visited{ Color: #FF9BFF; text-decoration: none;}\n";
echo "A:Active{ Color: #05FA30;}\n";
echo "A:hover{ Color: #05FA30}\n";
echo "BODY{Margin-Left: 0; Margin-Right:0; Margin-Top: 50; Margin-Bottom: 10;}\n";
echo "--></style>\n";
echo "</HEAD>\n";
echo "<BODY TEXT=\"BLACK\" BGCOLOR=\"#F7EDCE\" BACKGROUND=\"\">\n";
echo "<div class=\"body\">\n";
if(@$error=="nick")
{$msg="<font color=\"red\" size=\"5\"><b>User was not found</b></font><br><br>";}
if(@$error=="login")
{$msg="<font color=\"red\" size=\"5\"><b>Your login failed</b></font><br><br>";}
echo @$msg;
echo "<font color=\"black\" size=\"5\">Введите свой \"игровой\" ник и пароль.</font>\n";
echo "<b><form method=\"POST\" action=\"foto.php?ref=$ref\" name=\"auth\">\n";
echo "Ник:<br>\n"; 
echo "<input type=\"text\" name=\"us\" maxlength=\"15\"  size=\"15\"><br>\n"; 
echo "Пароль:<br>\n";
echo "<input type=\"text\" name=\"ps\" maxlength=\"15\"  size=\"15\"><br>\n";  
echo "<input type=\"submit\" value=\"Вход\" name=\"enter\" onMouseOver=\"style.color='#00FF40'\" onMouseOut=\"style.color='black'\"></b>\n"; 
echo "</BODY>\n"; 
echo "</HTML>\n";
 exit;
}
if(isset($us)){
$us=strtr($us,array("а"=>"Р°","б"=>"Р±","в"=>"РІ","г"=>"Рі","д"=>"Рґ","е"=>"Рµ","ё"=>"С‘","ж"=>"Р¶","з"=>"Р·","и"=>"Рё","й"=>"Р№","к"=>"Рє","л"=>"Р»","м"=>"Рј","н"=>"РЅ","о"=>"Рѕ","п"=>"Рї","р"=>"СЂ","с"=>"СЃ","т"=>"С‚","у"=>"Сѓ","ф"=>"С„","х"=>"С…","ц"=>"С†","ч"=>"С‡","ш"=>"С€","щ"=>"С‰","ъ"=>"СЉ","ы"=>"С‹","ь"=>"СЊ","э"=>"СЌ","ю"=>"СЋ","я"=>"СЏ","А"=>"Рђ","Б"=>"Р‘","В"=>"Р’","Г"=>"Р“","Д"=>"Р”","Е"=>"Р•","Ё"=>"РЃ","Ж"=>"Р–","З"=>"Р—","И"=>"Р","Й"=>"Р™","К"=>"Рљ","Л"=>"Р›","М"=>"Рњ","Н"=>"Рќ","О"=>"Рћ","П"=>"Рџ","Р"=>"Р ","С"=>"РЎ","Т"=>"Рў","У"=>"РЈ","Ф"=>"Р¤","Х"=>"РҐ","Ц"=>"Р¦","Ч"=>"Р§","Ш"=>"РЁ","Щ"=>"Р©","Ъ"=>"РЄ","Ы"=>"Р«","Ь"=>"Р¬","Э"=>"Р­","Ю"=>"Р®","Я"=>"РЇ")); 
}
$link = @mysql_pconnect ($server, $user, $dbpass)
                or die ("Can not connect to MySQL");
     
     @mysql_select_db($dbname) or die ("error select the database...");
               
               if(isset($id)){
        $result = @mysql_query ("Select * from users where id='".$id."'");    
        } else {
                         if ($us=="") {
header ("Location: foto.php?error=nick");
mysql_close ($link);
exit;
}               
                function rus_to_k($str){
                $str = str_replace("Р°","a",$str); 
                $str = str_replace("Р±","b",$str);
                $str = str_replace("РІ","v",$str);
                $str = str_replace("Рі","g",$str);
                $str = str_replace("Рґ","d",$str);
                $str = str_replace("Рµ","e",$str); 
                $str = str_replace("С‘","e",$str); 
                $str = str_replace("Р¶","j",$str);
                $str = str_replace("Р·","z",$str);
                $str = str_replace("Рё","i",$str);
                $str = str_replace("Р№","y",$str);
                $str = str_replace("Рє","k",$str);
                $str = str_replace("Р»","l",$str); 
                $str = str_replace("Рј","m",$str);
                $str = str_replace("РЅ","n",$str);
                $str = str_replace("Рѕ","o",$str);
                $str = str_replace("Рї","p",$str);
                $str = str_replace("СЂ","r",$str);
                $str = str_replace("СЃ","s",$str); 
                $str = str_replace("С‚","t",$str);
                $str = str_replace("Сѓ","u",$str);
                $str = str_replace("С„","f",$str);
                $str = str_replace("С…","h",$str);
                $str = str_replace("С‡","c",$str);
                $str = str_replace("С†","q",$str);//ч 
                $str = str_replace("С€","w",$str);
                $str = str_replace("С‰",">",$str);//щ
                $str = str_replace("СЊ","<",$str);//ъ
                $str = str_replace("С‹","x",$str);//ы
                $str = str_replace("СЉ",".",$str);//ь
                $str = str_replace("СЌ",":",$str);//э 
                $str = str_replace("СЋ",";",$str);//ю
                $str = str_replace("СЏ","}",$str);//я
                $str = str_replace("Рђ","a",$str);
                $str = str_replace("Р‘","b",$str);
                $str = str_replace("Р’","v",$str);
                $str = str_replace("Р“","g",$str); 
                $str = str_replace("Р”","d",$str);
                $str = str_replace("Р•","e",$str);
                $str = str_replace("РЃ","e",$str);
                $str = str_replace("Р–","j",$str);
                $str = str_replace("Р—","z",$str);
                $str = str_replace("Р","i",$str); 
                $str = str_replace("Р™","y",$str);
                $str = str_replace("Рљ","k",$str);
                $str = str_replace("Р›","l",$str);
                $str = str_replace("Рњ","m",$str);
                $str = str_replace("Рќ","n",$str);
                $str = str_replace("Рћ","o",$str); 
                $str = str_replace("Рџ","p",$str);
                $str = str_replace("Р ","r",$str);
                $str = str_replace("РЎ","s",$str);
                $str = str_replace("Рў","t",$str);
                $str = str_replace("РЈ","u",$str);
                $str = str_replace("Р¤","f",$str);
                $str = str_replace("РҐ","h",$str);
                $str = str_replace("Р§","c",$str);
                $str = str_replace("Р¦","q",$str);
                $str = str_replace("РЁ","w",$str); 
                $str = str_replace("Р©",">",$str);
                $str = str_replace("Р¬","<",$str);
                $str = str_replace("Р«","x",$str);
                $str = str_replace("РЄ",".",$str);
                $str = str_replace("Р­",":",$str);
                $str = str_replace("Р®",";",$str);
                $str = str_replace("РЇ","}",$str);
                return $str;
                }
        
    $latuser=strtolower($us);
    $ruser = rus_to_k($us);
    if($ruser==$us){
   $result = mysql_query ("Select * from users where nick = '".$latuser."'"); 
    } else {
   $result = mysql_query ("select * from users where nick = '".$ruser."'");
    }
    }     
        
        if (mysql_affected_rows() == 0) {
header ("Location: foto.php?error=nick");
mysql_close ($link);
exit;
}
        
$row = mysql_fetch_array ($result);
if ($ps !== $row["pass"]){
header ("Location: foto.php?error=login");
mysql_close ($link);
exit;
}  
if(!isset($id))$id=$row["id"];
$us=$row["nick"];

if(isset($go))
{
if(!isset($file))
{
 header ("Location: foto.php?id=$id&ps=$ps&ref=$ref&error=file");
 exit;
 }
$size = filesize($file);
$par = GetImageSize($file); 
$filetype = $_FILES['file']['type'];
if($filetype!=="image/gif" && $filetype!=="image/jpeg")
{
 header ("Location: foto.php?id=$id&ps=$ps&ref=$ref&error=nogif");
 exit;
 }
if($size>512000)
{
 header ("Location: foto.php?id=$id&ps=$ps&ref=$ref&error=size");
 exit;
 }
if(($par[0]>500)||($par[1]>500))
 {
 header ("Location: foto.php?id=$id&ps=$ps&ref=$ref&error=pix");
 exit;
 }
if($filetype=="image/gif") {
$foto=$id.".gif";} else {$foto=$id.".jpg";}
if (file_exists ("photos/$foto"))
{
unlink ("photos/$id.gif");
unlink ("photos/$id.jpg");
}
Copy($file, "photos/$foto"); 
@mysql_query ("Update users set img='".$foto."' where id ='".$id."'");
$msg="<b>Ваша фотография добавлена</b><br>";
}
$result = mysql_query ("select * from users where id = '".$id."'");
$row = mysql_fetch_array ($result);
echo "<HTML>\n";
echo "<HEAD>\n";
echo "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=windows-1251\">\n";
echo "<TITLE>Добавить фото</TITLE>\n";
echo "<style TYPE=\"text/css\"><!--\n";
echo ".body{ text-align: center; font-family: Arial; font-size: 12pt;}\n";
echo "A:Link{ Color: #FF4A4A; text-decoration: none;}\n";
echo "A:Visited{ Color: #FF9BFF; text-decoration: none;}\n";
echo "A:Active{ Color: #05FA30;}\n";
echo "A:hover{ Color: #05FA30}\n";
echo "BODY{Margin-Left: 0; Margin-Right:0; Margin-Top: 0; Margin-Bottom: 10;}\n";
echo "--></style>\n";
echo "</HEAD>\n";
echo "<BODY TEXT=\"BLACK\" BGCOLOR=\"#F7EDCE\" BACKGROUND=\"\">\n";
echo "<div class=\"body\">\n";
if(@$error=="file")
{$msg="<font color=\"red\" size=\"5\"><b>Вы не указали файл!</b></font><br><br>";}
if(@$error=="nogif")
{$msg="<font color=\"red\" size=\"5\"><b>Указанный вами файл не является Gif или JPG файлом!</b></font><br><br>";

if (file_exists ("photos/$foto"))
{
unlink ("photos/$id.gif");
unlink ("photos/$id.jpg");
$msg="<font color=\"red\" size=\"4\"><b>Предыдущее фото удалено!</b></font><br><br>";
@mysql_query ("Update users set img='' where id ='".$id."'");
}
}
if(@$error=="size")
{$msg="<font color=\"red\" size=\"5\"><b>Слишком большой размер файла!</b></font><br><br>";}
if(@$error=="pix")
{$msg="<font color=\"red\" size=\"5\"><b>Неправильное разрешение фотографии!</b></font><br><br>";}
echo @$msg;
$nick=strtr($us,array("Р°"=>"а","Р±"=>"б","РІ"=>"в","Рі"=>"г","Рґ"=>"д","Рµ"=>"е","С‘"=>"ё","Р¶"=>"ж","Р·"=>"з","Рё"=>"и","Р№"=>"й","Рє"=>"к","Р»"=>"л","Рј"=>"м","РЅ"=>"н","Рѕ"=>"о","Рї"=>"п","СЂ"=>"р","СЃ"=>"с","С‚"=>"т","Сѓ"=>"у","С„"=>"ф","С…"=>"х","С†"=>"ц","С‡"=>"ч","С€"=>"ш","С‰"=>"щ","СЉ"=>"ъ","С‹"=>"ы","СЊ"=>"ь","СЌ"=>"э","СЋ"=>"ю","СЏ"=>"я",
"Рђ"=>"А","Р‘"=>"Б","Р’"=>"В","Р“"=>"Г","Р”"=>"Д","Р•"=>"Е","РЃ"=>"Ё","Р–"=>"Ж","Р—"=>"З","Р"=>"И","Р™"=>"Й","Рљ"=>"К","Р›"=>"Л","Рњ"=>"М","Рќ"=>"Н","Рћ"=>"О","Рџ"=>"П","Р "=>"Р","РЎ"=>"С","Рў"=>"Т","РЈ"=>"У","Р¤"=>"Ф","РҐ"=>"Х","Р¦"=>"Ц","Р§"=>"Ч","РЁ"=>"Ш","Р©"=>"Щ","РЄ"=>"Ъ","Р«"=>"Ы","Р¬"=>"Ь","Р­"=>"Э","Р®"=>"Ю","РЇ"=>"Я"));
echo "<font color=\"blue\" size=\"6\">$nick</font><br>\n";
echo "<b>
1.Загружаемая фотка обязательно должна быть в формате JPG или GIF.<br/>
2.Pазрешением не более 500х500 пикселей(рекомендуемое разрешение 128х128). <br/>
3.Pазмером не более 512кб. <br/>
4.Если вы хотите заменить свою фотку, то просто загрузите новую, а старая автоматически удалится.<br/>
5.Если вы хотите удалить фото, то просто нажмите отправить ничего не указывая.</b><br>\n";
if($row["img"]!="")
{
echo "У вас установленно фото: <img align=\"center\" src=\"photos/".$row["img"]."\">\n";
}
echo "<br><br><form ENCTYPE=\"multipart/form-data\" action=\"foto.php?id=$id&amp;ps=$ps&amp;ref=$ref\" method=\"post\">\n";
echo "<b>Прикрепить фотку:</b><br>\n";
echo "<INPUT NAME=\"file\" TYPE=\"file\" SIZE=\"20\"><br>\n";
echo "<input type=\"submit\" name=\"go\" value=\"Отправить\" onMouseOver=\"style.color='#00FF40'\" onMouseOut=\"style.color='black'\">\n";
echo "</form>\n";
echo "</BODY>\n"; 
echo "</HTML>\n";
mysql_close ($link);

?>
