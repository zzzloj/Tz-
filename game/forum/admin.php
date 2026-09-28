<?php 
include "./config.inc.php";
include "system.php";
header("Content-type: text/vnd.wap.wml");
header("Cache-Control: no-cache");
echo "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n";
echo "<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\""." \"http://www.wapforum.org/DTD/wml_1.1.xml\">\r\n";
mysql_connect($bd_host,$bd_name,$bd_pass);
mysql_query("SET NAMES 'cp1251'"); 
mysql_query("SET CHARACTER SET 'cp1251'"); 
mysql_query("SET collation_connection=cp1251_general_ci"); 
mysql_query("SET character_set_results=cp1251"); 
mysql_query("SET character_set_client=cp1251"); 
mysql_query("SET collation_database=cp1251_general_ci");
mysql_select_db($bd_bd) ;
echo "<wml><card title=\"$atitle\">\r\n";
echo "<p align=\"center\">\r\n";
echo "$ahead\r\n";
if(!$mod)
{
echo '<input type="text" name="pass"/><br/>
<anchor>вход
<go href="admin.php?mod=panel" method="post">
<postfield name="pass" value="$(pass)"/>
</go>
</anchor>';
}
if($mod=='panel')
{
	if($pass==$apass)
	{
		$sections=mysql_fetch_array(mysql_query("SELECT * FROM `fsection`"));
		if(!$sections) {echo "Разделы не определены в базе!<br/>"; }
		else
		{
$lists=mysql_query("select * from `fsection` order by `position` desc");
        echo "</p><p>";
while($datas=mysql_fetch_array($lists))
        {
	echo "::$datas[1]<br/>[<a href=\"admin.php?mod=up&amp;sect=$datas[0]&amp;pass=$pass\">вверх</a>] [<a href=\"admin.php?mod=down&amp;sect=$datas[0]&amp;pass=$pass\">вниз</a>] [<a href=\"admin.php?mod=edit&amp;sect=$datas[0]&amp;pass=$pass\">изм.</a>] [<a href=\"admin.php?mod=del&amp;sect=$datas[0]&amp;pass=$pass\">удал.</a>]<br/>";
	    }
	    echo "</p><p align=\"center\">";
		}
		echo "<br/><a href=\"admin.php?mod=msect&amp;pass=$pass\">создать раздел</a><br/><a href=\"admin.php?mod=moder&amp;pass=$pass\">модераторы</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
if($mod=='msect')
{
	if($pass==$apass)
	{
		if(!$nsect)
		{
echo "<br/>Создать раздел?<br/>";
echo '<input type="text" name="nsect" maxlength="100"/><br/>
<anchor>создать
<go href="admin.php?mod=msect&amp;pass=$pass" method="post">
<postfield name="nsect" value="$(nsect)"/>
</go>
</anchor>';
		}
		else
		{
		    $nsect=del(substr(trim($nsect),0,200));
			$tsect=time();
	        $tposit=mysql_fetch_array(mysql_query("SELECT MAX(position) FROM `fsection`"));
	        $tposit=legacy_num($tposit[0])+1;
	$addsect=mysql_query("INSERT INTO `fsection` VALUES('', '$nsect', '$tsect', '$tposit')");
		if($addsect){echo "Раздел успешно создан!";} else{echo "Ошибка!";}
		}
		echo "<br/><a href=\"$PHP_SELF?mod=panel&amp;pass=$pass\">админ-панель</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
if($mod=='del')
{
	if($pass==$apass)
	{
		if(!$vote)
		{
echo "<br/>Удалить раздел?<br/><a href=\"admin.php?mod=del&amp;vote=yes&amp;sect=$sect&amp;pass=$pass\">да</a>";
		}
		else
		{
		$delsect=mysql_query("DELETE FROM `fsection` WHERE `id`='$sect'");
		if($delsect){echo "Раздел удален!";} else{echo "Ошибка!";}
		}
		echo "<br/><a href=\"$PHP_SELF?mod=panel&amp;pass=$pass\">админ-панель</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
if($mod=='up')
{
	if($pass==$apass)
	{
	    $tposit=mysql_fetch_array(mysql_query("SELECT position FROM `fsection` WHERE `id`='$sect'"));
	    $newposit=legacy_num($tposit[0])+1;
		$upsect=mysql_query("UPDATE `fsection` SET `position`='$newposit' WHERE `id`='$sect'");
		if($upsect){echo "Раздел передвинут!";} else{echo "Ошибка!";}
		echo "<br/><a href=\"$PHP_SELF?mod=panel&amp;pass=$pass\">админ-панель</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
if($mod=='down')
{
	if($pass==$apass)
	{
	    $tposit=mysql_fetch_array(mysql_query("SELECT position FROM `fsection` WHERE `id`='$sect'"));
	    $newposit=legacy_num($tposit[0])-1;
		$downsect=mysql_query("UPDATE `fsection` SET `position`='$newposit' WHERE `id`='$sect'");
		if($downsect){echo "Раздел передвинут!";} else{echo "Ошибка!";}
		echo "<br/><a href=\"$PHP_SELF?mod=panel&amp;pass=$pass\">админ-панель</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
if($mod=='edit')
{
	if($pass==$apass)
	{
	$edits=mysql_fetch_array(mysql_query("SELECT name FROM `fsection` WHERE `id`='$sect'"));
	if(!$nname)
		{
echo "<input type=\"text\" name=\"nname\" value=\"$edits[0]\"/><br/>
<anchor>переименовать
<go href=\"admin.php?mod=edit&amp;pass=$pass&amp;sect=$sect\" method=\"post\">
<postfield name=\"nname\" value=\"$(nname)\"/>
</go>
</anchor>";
        }
	    else
	    {
		$editsect=mysql_query("UPDATE `fsection` SET `name`='$nname' WHERE `id`='$sect'");
		if($editsect){echo "Раздел переименован!";} else{echo "Ошибка!";}
		}
		echo "<br/><a href=\"$PHP_SELF?mod=panel&amp;pass=$pass\">админ-панель</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
if($mod=='moder')
{
	if($pass==$apass)
	{
	if($pass==$apass)
	{
		$moders=mysql_fetch_array(mysql_query("SELECT * FROM `fmoders`"));
		if(!$moders) {echo "Модераторы не определены!<br/>"; }
		else
		{
$listm=mysql_query("select * from `fmoders`");
        echo "</p><p>";
while($datam=mysql_fetch_array($listm))
        {
		$moders=mysql_fetch_array(mysql_query("SELECT * FROM `fmoders` where id='$datam[0]'"));
		if($moders[2]!='*'){$msect=mysql_fetch_array(mysql_query("SELECT * FROM `fsection` where id='$moders[2]'")); $msect=$msect[1];}
		else {$msect='все разделы';}
	echo "$datam[1] ($msect)<br/>[<a href=\"admin.php?mod=delmod&amp;moder=$datam[0]&amp;pass=$pass\">удал.</a>]<br/>";
	    }
	    echo "</p><p align=\"center\">";
		}
		echo "<br/><a href=\"$PHP_SELF?mod=mmod&amp;pass=$pass\">добавить модератора</a>";
		}
		echo "<br/><a href=\"$PHP_SELF?mod=panel&amp;pass=$pass\">админ-панель</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
if($mod=='mmod')
{
	if($pass==$apass)
	{
	if(!$nmod)
		{
echo "назначить модератора<br/>логин юзера<br/><input type=\"text\" name=\"nmod\"/><br/>раздел<br/>
	<select name=\"section\">";
        	echo "<option value=\"*\">все разделы</option>";
$lists=mysql_query("select * from `fsection` order by `position` desc");
while($datas=mysql_fetch_array($lists))
        {
        	echo "<option value=\"$datas[0]\">$datas[1]</option>";
        }
echo "</select><br/>
<anchor>назначить
<go href=\"admin.php?mod=mmod&amp;pass=$pass&amp;sect=$sect\" method=\"post\">
<postfield name=\"nmod\" value=\"$(nmod)\"/>
<postfield name=\"section\" value=\"$(section)\"/>
</go>
</anchor>";
        }
	    else
	    {
		$addmod=mysql_query("INSERT INTO `fmoders` VALUES ('', '$nmod', '$section')");
		if($addmod){echo "Модератор добавлен!";} else{echo "Ошибка!";}
		}
		echo "<br/><a href=\"$PHP_SELF?mod=panel&amp;pass=$pass\">админ-панель</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
if($mod=='delmod')
{
	if($pass==$apass)
	{
		if(!$vote)
		{
echo "<br/>Удалить модератора?<br/><a href=\"admin.php?mod=delmod&amp;vote=yes&amp;moder=$moder&amp;pass=$pass\">да</a>";
		}
		else
		{
		$delm=mysql_query("DELETE FROM `fmoders` WHERE `id`='$moder'");
		if($delm){echo "Модератор удален!";} else{echo "Ошибка!";}
		}
		echo "<br/><a href=\"$PHP_SELF?mod=panel&amp;pass=$pass\">админ-панель</a>";
	}
	else {echo "$errorlog $exitlog"; exit;}
}
echo $exitlog;
?>