<?php 

$ip = ("$REMOTE_ADDR");
if ($login=='akt' || $ip=='217.144.164.164' || strtolower($login)=='alatiel' || strtolower($login)=='fox' || strtolower($login)=='liska' || strtolower($login)=='anjelika' || strtolower($login)=='erra' || ($brouzer=='SonyEricssonK500i/R2AA SEMC-Browser/4.0.2 Profile/MIDP-2.0 Configuration/CLDC-1.1' && $ip=='83.149.48.211')) {

$log_save=$login." - ".$ip." - ".$QUERY_STRING." - ".date("d/m Y H:i",time())."\r\n";
$file=fopen("./z.txt","a+");@fputs($file,$log_save);@fclose($file);
header ("Location: http://mag.su");
}

require("gzip.php");
require("../datafunc.php");
require("config.inc.php");
require("system.php");
header ("Expires: Thu, 01 Jan 1970 00:00:01 GMT");
header ("Last-Modified: " . gmdate("D, d M Y H:i:s") . " GMT");
header ("Cache-Control: no-cache, no-store, must-revalidate, max-age=0");
header ("Pragma: no-cache");
header("Content-type:text/vnd.wap.wml;charset=utf-8");
echo "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"; 
echo "<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\""." \"http://www.wapforum.org/DTD/wml_1.1.xml\">"; 
echo "<wml><card id=\"main\" title=\"$title\">";
echo "<p>";
echo "$head";
openDB();
$checkuser=checkpass($login,$pass, $fields, &$result,$skippass=0);
  mysql_close();
if(!empty($checkuser)) 
 {
  	  echo "<br/>Логин: <input name=\"login\" emptyok=\"true\" type=\"text\" maxlength=\"10\" size=\"10\"/>
<br/>Пароль: <input name=\"pass\" emptyok=\"true\" type=\"password\" value=\"\" maxlength=\"10\" size=\"10\"/>
<br/>
		  <anchor>
		  вход
		  <go href=\"$PHP_SELF?login=$(login)&amp;p=$(pass)&amp;rnd=".rand(1,99)."\" method=\"post\">
<postfield name=\"login\" value=\"$(login)\"/>
<postfield name=\"pass\" value=\"$(pass)\"/>
		  </go></anchor>
";
  echo $exitlog;
}   
else
  { 
mysql_connect($bd_host,$bd_name,$bd_pass);
mysql_query("SET NAMES 'cp1251'"); 
mysql_query("SET CHARACTER SET 'cp1251'"); 
mysql_query("SET collation_connection=cp1251_general_ci"); 
mysql_query("SET character_set_results=cp1251"); 
mysql_query("SET character_set_client=cp1251"); 
mysql_query("SET collation_database=cp1251_general_ci");
mysql_select_db($bd_bd);
    if(empty($pass)) {echo "$errorlog $exitlog"; exit;}
    if(empty($login)) {echo "$errorlog $exitlog"; exit;}
	$check_ban=mysql_fetch_array(mysql_query("SELECT * FROM `fban` WHERE ulogin='$login'"));
	if($check_ban[0] && $check_ban[3]>time())
	 {
	 	 $timeban=$check_ban[3];
	 	 $timeban=date("H.i/d.m.Y",$timeban+$sdvig);
	 	 $timeban="выход на свободу<br/>$timeban<br/>";
	 echo "$errorban $timeban $exitlog"; exit;
	 }
	$stt=time();
	$conline=mysql_fetch_array(mysql_query("SELECT * FROM `fonline` WHERE ulogin='$login'"));
	if(!$conline[0])
	{
    $addonline=mysql_query("INSERT INTO `fonline` VALUES('', '$login', '$stt')");
    }
	$ent=$stt-$tonline;
    $delonline=mysql_query("DELETE FROM `fonline` WHERE `time`<'$ent'");	
	$online=mysql_num_rows(mysql_query("select * from `fonline`"));
    if(!$act)
	{
		$sections=mysql_fetch_array(mysql_query("SELECT * FROM `fsection`"));
		if(!$sections) {echo "$errorsec $exitlog"; exit; }
		else
		{
$lists=mysql_query("SELECT * FROM `fsection` ORDER BY `position` DESC");
        echo "<br/>---<br/>";
while($datas=mysql_fetch_array($lists))
        {
$countt=mysql_num_rows(mysql_query("select * from `fthemes` where fs='$datas[0]'"));
$countp=mysql_num_rows(mysql_query("select * from `fposts` where fs='$datas[0]'"));
	echo "<a href=\"index.php?act=sect&amp;sect=$datas[0]&amp;login=$login&amp;pass=$pass\">$datas[1]</a> ($countt/$countp)<br/>---<br/>";
	    }
			echo "Онлайн: <a href=\"index.php?act=online&amp;login=$login&amp;pass=$pass\">$online</a>";
}
	}
  if($act=='sect')
     {     
          $section=mysql_fetch_array(mysql_query("select name from `fsection`where id='$sect'"));
          echo "$section[0]<br/>---<br/>";

                    $tem=mysql_fetch_array(mysql_query("select * from `fthemes` where fs='$sect'"));
          if(!$tem) {echo "$errortem"; }
          else
          {
if(!$pg) {$pg=1; $ots=0; $dot=$quota_t;}
else {$ots=$pg*$quota_t-$quota_t; $dot=$quota_t;}
$yyy=$ots+$quota_t; $whhh=$ots; if($whhh=='0'){$whhh='1';}
$listt=mysql_query("SELECT * FROM `fthemes` WHERE fs='$sect' ORDER BY 'time' DESC LIMIT $ots,$dot");
//$listt=mysql_query("select * from `fthemes`  where fs='$sect' order by 'time' asc limit $ots,$dot");
         while($datat=mysql_fetch_array($listt))
{
     $ta=date("H.i/d.m.y",$datat[4]+$sdvig);
$count_t=mysql_num_rows(mysql_query("select * from `fthemes` where fs='$sect'"));
$pcount=mysql_num_rows(mysql_query("select * from `fposts` where tid='$datat[0]'"));
   $whois=mysql_fetch_array(mysql_query("select `ulogin` from `fposts` where tid='$datat[0]' order by `id` DESC"));
if($pcount>0) {$posts="$pcount ($whois[0])";}
   else {$posts="нет";}
   $datat[3]=str_replace("%","",$datat[3]);
   $datat[3]=str_replace("/","",$datat[3]);
echo "$datat[2]: <a href=\"index.php?act=tema&amp;tema=$datat[0]&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">$datat[3]</a> <small>($ta)</small>";
   if($datat[5]=='99999999') {echo '[!]';}
   if($datat[6]=='1') {echo '[x]';}

echo "<br/><small>ответов: $posts</small><br />---<br/>\n";
$moderlog=mysql_fetch_array(mysql_query("select * from `fmoders` where ulogin='$login'"));
if($moderlog[0])
{  
if($moderlog[2]==$sect || $moderlog[2]=='*')
{
echo "<a href=\"index.php?act=mtem&amp;sect=$sect&amp;tema=$datat[0]&amp;login=$login&amp;pass=$pass\">модерация</a><br/>---<br/>";
}
}
}
     if($count_t>0)
     {
echo "<small>Темы $whhh-$yyy из $count_t</small><br/>";
$totalp=ceil($count_t/$quota_t);
echo "<small>Страница $pg из $totalp</small><br/>";
$nextp=$pg+1;
$prevp=$pg-1;
if($pg==1 and $totalp>1) {echo "<small>[<a href=\"index.php?pg=$nextp&amp;act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">далее</a>]</small>";} 
if($pg>1 and $totalp>$pg) {echo "<small>[<a href=\"$PHP_SELF?pg=$prevp&amp;act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад</a>] [<a href=\"$PHP_SELF?pg=$nextp&amp;act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">далее</a>]</small>";} 
if($pg==$totalp and $pg!=1) {echo "<small>[<a href=\"$PHP_SELF?pg=$prevp&amp;act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад</a>]</small>";}
if($totalp>1)
{
echo "<br/><small><input type=\"text\" name=\"pg\" size=\"2\" value=\"$totalp\"/>
<anchor> перейти
<go href=\"index.php?act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\" method=\"post\">
<postfield name=\"pg\" value=\"$(pg)\"/>
</go>
</anchor></small>";
}
     }
          }
         echo "<br/>---<br/><a href=\"index.php?act=ntem&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">новая тема</a><br/><a href=\"index.php?login=$login&amp;pass=$pass\">разделы</a><br/>---";

     }



	if($act=='ntem')
	{
		if(!$tname)
		{
			$rrand=rand(1,99);
			echo "<br/>название темы<br/>
				<input type=\"hidden\" name=\"r\" value=\"$rrand\" />
	<input type=\"text\" name=\"tname$rrand\" maxlength=\"100\" value=\"\"/><br/>
		транслит: <select name=\"translit\">
		<option value=\"1\">нет</option>
		<option value=\"2\">да</option>
		</select><br/>
<anchor>создать
<go href=\"index.php?act=ntem&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;rand=$rrand\" method=\"post\">
<postfield name=\"tname\" value=\"$(tname$rrand)\"/>
<postfield name=\"translit\" value=\"$(translit)\"/>
</go>
</anchor>";
		}
		else
		{ 
		    if($translit=='2') {$tname=trans($tname);}
            $tname=ChangeSt(substr(trim($tname),0,200));
			$ttime=time();
$posit=mysql_fetch_array(mysql_query("SELECT MAX(position) FROM `fthemes` WHERE fs='$sect' and position!='99999999'"));
$posit=$posit[0]+1;
$checkt=mysql_fetch_array(mysql_query("SELECT name FROM `fthemes` WHERE name='$tname' and fs='$sect'"));
$checksect=mysql_fetch_array(mysql_query("SELECT * FROM `fsection` where id='$sect'"));
if($checkt[0] || empty($checksect)) {echo "$errort";}
else
{
$addtem=mysql_query("INSERT INTO `fthemes` VALUES ('', '$sect', '$login', '$tname', '$ttime', '$posit', '0')");
$temid=mysql_fetch_array(mysql_query("SELECT id FROM `fthemes` WHERE name='$tname' and fs='$sect' LIMIT 1"));	
		if($addtem){echo "Тема успешно создана! <br/><a href=\"index.php?act=tema&amp;tema=$temid[0]&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">к теме</a><br/>";} else{echo "Ошибка!";}
}
		}
echo "<br/>---<br/><a href=\"index.php?act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад в раздел</a><br/><a href=\"index.php?login=$login&amp;pass=$pass\">разделы</a><br/>---<br/>";

	}
	if($act=='mtem')
	{
	$modertem=mysql_fetch_array(mysql_query("SELECT * FROM `fthemes` where id='$tema'"));
	if(!$mod)
	{
		echo "<br/>---<br/>тема: $modertem[3]<br/>";
echo "<a href=\"index.php?act=mtem&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema&amp;mod=del\">удалить</a><br/>";
if($modertem[6]=='0')
{
echo "<a href=\"index.php?act=mtem&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema&amp;mod=close\">закрыть</a><br/>";
}
else
{
echo "<a href=\"index.php?act=mtem&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema&amp;mod=open\">открыть</a><br/>";
	
}
if($modertem[5]!='99999999')
{
echo "<a href=\"index.php?act=mtem&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema&amp;mod=fix\">закрепить</a><br/>";
}
else
{
echo "<a href=\"index.php?act=mtem&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema&amp;mod=unfix\">открепить</a><br/>";
	
}
			echo "<br/>название темы<br/>
				<input type=\"text\" name=\"ename\" maxlength=\"100\" value=\"$modertem[3]\"/><br/>
<anchor>изменить
<go href=\"index.php?act=mtem&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema&amp;mod=ename\" method=\"post\">
<postfield name=\"ename\" value=\"$(ename)\"/>
</go>
</anchor>";
	}
	if($mod=='del')
	{
$delt=mysql_query("DELETE FROM `fthemes` WHERE `id`='$tema'");	
$delp=mysql_query("DELETE FROM `fposts` WHERE `tid`='$tema'");	
		if($delt){echo "Тема удалена!";} else{echo "Ошибка!";}
	}
	if($mod=='close')
	{
		$closet=mysql_query("UPDATE `fthemes` SET `status`='1' WHERE `id`='$tema'");	
		if($closet){echo "Тема закрыта!";} else{echo "Ошибка!";}
	}
	if($mod=='open')
	{
		$opent=mysql_query("UPDATE `fthemes` SET `status`='0' WHERE `id`='$tema'");	
		if($opent){echo "Тема открыта!";} else{echo "Ошибка!";}
	}
	if($mod=='fix')
	{
		$fixt=mysql_query("UPDATE `fthemes` SET `position`='99999999' WHERE `id`='$tema'");	
		if($fixt){echo "Тема закреплена!";} else{echo "Ошибка!";}
	}
	if($mod=='unfix')
	{
		$unfixt=mysql_query("UPDATE `fthemes` SET `position`='0' WHERE `id`='$tema'");	
		if($unfixt){echo "Тема откреплена!";} else{echo "Ошибка!";}
	}
	if($mod=='ename')
	{
		if(!$ename) {echo "Вы не ввели новое имя темы!";}
		else
		{
			$ename=ChangeSt($ename);
		$unfixt=mysql_query("UPDATE `fthemes` SET `name`='$ename' WHERE `id`='$tema'");	
		if($unfixt){echo "Тема переименована!";} else{echo "Ошибка!";}
		}
	}
echo "<br/>---<br/><a href=\"index.php?act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад в раздел</a><br/><a href=\"index.php?login=$login&amp;pass=$pass\">разделы</a><br/>---<br/>";

	}
	if($act=='tema')
	{	
$section=mysql_fetch_array(mysql_query("SELECT name FROM `fsection`where id='$sect'"));
$tname=mysql_fetch_array(mysql_query("SELECT name FROM `fthemes`where id='$tema'"));
		echo "$section[0]<br/>Тема: $tname[0]<br/>---<br/>";

				$ans=mysql_fetch_array(mysql_query("SELECT * FROM `fposts` where tid='$tema'"));
		if(!$ans) {echo "$errorp"; }
		else
		{
if(!$pg) {$pg=1; $ots=0; $dot=$quota_p;}
else {$ots=$pg*$quota_p-$quota_p; $dot=$quota_p;}
$yyy=$ots+$quota_p; $whhh=$ots; if($whhh=='0'){$whhh='1';}
$listp=mysql_query("select * from `fposts` where tid='$tema' order by 'time' asc LIMIT $ots,$dot");
//$listp=mysql_query("select * from `fthemes` where fs='$sect' order by 'time' DESC LIMIT $ots,$dot");
    while($datap=mysql_fetch_array($listp))
{
	$ta=date("H.i/d.m.y",$datap[5]+$sdvig);
$count_p=mysql_num_rows(mysql_query("select * from `fposts` where tid='$tema'"));
echo "<a href=\"index.php?act=post&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass&amp;touser=$datap[3]\">$datap[3]</a><small>: $ta</small><br/><small>$datap[4]</small><br/>---<br/>\n";

$moderlog=mysql_fetch_array(mysql_query("SELECT * FROM `fmoders` where ulogin='$login'"));
if($moderlog[0])
{  
if($moderlog[2]==$sect || $moderlog[2]=='*')
{
echo "<a href=\"index.php?act=mpost&amp;post=$datap[0]&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">модерация</a><br/>---<br/>";
}
}
}
	if($count_p>0)
	{
echo "<small>Сообщения $whhh-$yyy из $count_p</small><br/>";
$totalp=ceil($count_p/$quota_p);
echo "<small>Страница $pg из $totalp</small><br/>";
$nextp=$pg+1;
$prevp=$pg-1;
if($pg==1 and $totalp>1) {echo "<small>[<a href=\"index.php?pg=$nextp&amp;act=tema&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">далее</a>]</small>";} 
if($pg>1 and $totalp>$pg) {echo "<small>[<a href=\"$PHP_SELF?pg=$prevp&amp;act=tema&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад</a>] [<a href=\"$PHP_SELF?pg=$nextp&amp;act=tema&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">далее</a>]</small>";} 
if($pg==$totalp and $pg!=1) {echo "<small>[<a href=\"$PHP_SELF?pg=$prevp&amp;act=tema&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад</a>]</small>";}
if($totalp>1)
{
echo "<br/><small><input type=\"text\" name=\"pg\" size=\"2\" value=\"".$totalp."\"/>
<anchor> перейти
<go href=\"index.php?act=tema&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\" method=\"post\">
<postfield name=\"pg\" value=\"$(pg)\"/>
</go>
</anchor></small>";
}
	}
		}
	    echo "<br/>---<br/>";
	    $checkc=mysql_fetch_array(mysql_query("SELECT status FROM `fthemes` WHERE id='$tema'"));
	    if($checkc[0]!='1')
	    {
echo "<a href=\"index.php?act=post&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">ответить</a><br/>";
		}
		else
		{
			echo "$errorclose<br/>";
		}
	    echo "<a href=\"index.php?act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад в раздел</a><br/><a href=\"index.php?act=ntem&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">новая тема</a><br/><a href=\"index.php?login=$login&amp;pass=$pass\">разделы</a><br/>---<br/>";

	}
	if($act=='post')
	{
		if(!$post)
		{
					$rrand=rand(1,99);
	echo "<br/>сообщение<br/>
				<input type=\"text\" name=\"post$rrand\" maxlength=\"200\" value=\"$touser\"/><br/>
		транслит: <select name=\"translit\">
		<option value=\"1\">нет</option>
		<option value=\"2\">да</option>
		</select><br/>
<anchor>добавить
<go href=\"index.php?act=post&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema\" method=\"post\">
<postfield name=\"post\" value=\"$(post$rrand)\"/>
<postfield name=\"translit\" value=\"$(translit)\"/>
</go>
</anchor>";
		}
		else
		{ 
			
			if($translit=='2') {$post=trans($post);}
		    $post=ChangeSt(substr(trim($post),0,400));
			$tpost=time();
$checkc=mysql_fetch_array(mysql_query("SELECT status FROM `fthemes` WHERE id='$tema'"));
if($checkc[0]!='0') {echo "$errorclose";}
else
{
$checkp=mysql_fetch_array(mysql_query("SELECT post FROM `fposts` WHERE post='$post' and tid='$tema'"));
if($checkp[0]) {echo "$errorpost";}
else
{
$addpost=mysql_query("INSERT INTO `fposts` VALUES ('', '$sect', '$tema', '$login', '$post', '$tpost')");
$uptema=mysql_query("update `fthemes` set `time`='".time()."' where `id`='$tema'");   	
		if($addpost){echo "Сообщение успешно добавлено!";} else{echo "Ошибка!";}
}
}
}
		echo "<br/>---<br/><a href=\"index.php?act=tema&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад к теме</a><br/><a href=\"index.php?act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад в раздел</a><br/><a href=\"index.php?login=$login&amp;pass=$pass\">разделы</a><br/>---<br/>";

	}
	if($act=='mpost')
	{
	$moderpost=mysql_fetch_array(mysql_query("SELECT * FROM `fposts` where id='$post'"));
	if(!$mod)
	{
		$prep=substr(trim($moderpost[4]),0,20);
		echo "<br/>---<br/>Пост: $prep...<br/>";
echo "<a href=\"index.php?act=mpost&amp;post=$post&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass&amp;mod=del\">удалить</a><br/>";
			echo "<input type=\"text\" name=\"epost\" maxlength=\"200\" value=\"$moderpost[4]\"/><br/>
<anchor>изменить
<go href=\"index.php?act=mpost&amp;post=$post&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema&amp;mod=epost\" method=\"post\">
<postfield name=\"epost\" value=\"$(epost)\"/>
</go>
</anchor>";
			echo "<br/>---<br/>";
			echo "Юзер: $moderpost[3]<br/>
				Бан на<br/>
				<select name=\"banto\">
				<option value=\"1\">1 день</option>
				<option value=\"3\">3 дня</option>
				<option value=\"7\">7 дней</option>
				<option value=\"15\">15 дней</option>
				<option value=\"30\">30 дней</option>
				</select>
				<br/>
<anchor>отправить
<go href=\"index.php?act=mpost&amp;login=$login&amp;pass=$pass&amp;sect=$sect&amp;tema=$tema&amp;mod=ban&amp;userban=$moderpost[3]\" method=\"post\">
<postfield name=\"bant\" value=\"$(banto)\"/>
</go>
</anchor>";
	}
	if($mod=='del')
	{
$delp=mysql_query("DELETE FROM `fposts` WHERE `id`='$post'");	
		if($delp){echo "Сообщение удалено!";} else{echo "Ошибка!";}
	}
	if($mod=='epost')
	{
		if(!$epost) {echo "Вы не ввели сообщение!";}
		else
		{
				    $epost=ChangeSt(substr(trim($epost),0,400));
		$editp=mysql_query("UPDATE `fposts` SET `post`='$epost' WHERE `id`='$post'");	
		if($editp){echo "Сообщение изменено!";} else{echo "Ошибка!";}
		}
	}
		if($mod=='ban')
	{
		$rtime=time();
		$tban=$rtime+$bant*86400;
$addban=mysql_query("INSERT INTO `fban` VALUES ('', '$userban', '$rtime','$tban')");	
		if($addban){echo "Юзер забанен!";} else{echo "Ошибка!";}
	}

echo "<br/>---<br/><a href=\"index.php?act=tema&amp;tema=$tema&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад к теме</a><br/><a href=\"index.php?act=sect&amp;sect=$sect&amp;login=$login&amp;pass=$pass\">назад в раздел</a><br/><a href=\"index.php?login=$login&amp;pass=$pass\">разделы</a><br/>---<br/>";

	}
	    if($act=='online')
	{
$listo=mysql_query("select * from `fonline` order by `time` DESC");
echo "онлайн на форуме<br/>";
while($datao=mysql_fetch_array($listo))
        {
        	$tin=date("H.i",$datao[2]+$sdvig);
	echo "$datao[1] ($tin)<br/>";
	    }
	    echo "<br/><a href=\"index.php?login=$login&amp;pass=$pass\">разделы</a><br/>---<br/>";
	}

echo $exitlog;
  mysql_close();
  }
?>