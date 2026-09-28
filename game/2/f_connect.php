<?php
error_reporting(0);
extract($_GET);
  extract($_POST);
  extract($_COOKIE);
  extract(isset($_SESSION) ? $_SESSION : array());
  extract($_SERVER);
$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp);
if(eregi("[^a-z0-9_.-]",$login) || $ip=='217.144.164.164' || $login=='u.alatiel' || $login=='u.fox' || $login=='u.liska' || $login=='u.anjelika' || $login=='u.erra' || ($brouzer=='SonyEricssonK500i/R2AA SEMC-Browser/4.0.2 Profile/MIDP-2.0 Configuration/CLDC-1.1' && $ip=='83.149.48.211')){
header ("Location: http://download.l2db.ru/original%20clients/L2_C5_Installer.zip"); exit;}

// константы должны совпадать с g.php
$g_admin = "u.admin";
$zero = "no_admin";
$PHP_SELF = "g.php";

        setlocale (LC_CTYPE, 'ru_RU.CP1251');
        function win2unicode ( $s ) { if ( (ord($s)>=192) & (ord($s)<=255) ) $hexvalue=dechex(ord($s)+848); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");}
        function translate($s) {return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s));}

        if (substr($login,0,2)!="u.") $login="u.".$login;
        $login=strtolower($login);
        $login=substr($login,0,15);

        clearstatcache();
        if (file_exists("online/".$login) && filesize("online/".$login)!=1) {if ($zx==$zero) $p.="&zx=".$zx; msg("Нажмите на ссылку ниже<br/><a href=\"g.php?site=connect2&login=$login&p=$p\">Продолжить</a>");}

        // создаем файл c точкой (длина 1 байт), чтобы нельзя было войти на 2 сервера сразу, потом удалим, если не изменится
        @ignore_user_abort(true);
        $file = fopen ("online/".$login, "w");
        if ($file!==false) {fwrite($file,".");fclose($file);} else msg("Ошибка создания файла");

        // проверим на других серверах
        $tmp=file("servers.dat");
        $srv=implode("",file("server.dat"));
        for($i=1;$i<count($tmp);$i++) if ($srv!=$i) {
                $s="";
                $s=@implode("",@file(trim($tmp[$i])."f_site_common.php?login=".$login));
                if ($s=="yes") {$loc=""; msg("Ваш персонаж находится на <a href=\"".trim($tmp[$i])."g.php?site=connect2&login=$login&p=$p&r=".rand(1,99)."\">сервере ".$i."</a>, чтобы играть на этом (сервер ".$srv."), подождите 10 минут и попробуйте войти заново.","Территория Зла");}
                if ($s!="no") {$loc=""; msg("Сервер не отвечает, попробуйте зайти на <a href=\"f_newserver.php?login=$login&amp;p=$p\">другой сервер</a> или повторите через несколько минут.","Территория Зла");}
                }
       //    echo 1;
//echo "aa";
        require_once("datafunc.php");
        // получаем данные с основного сервера
       //  echo 1;
                        $res=GetData(substr($login,2),$p,$data,$srv);
                        if ($res=="Логин не найден" || $res=="Неверный пароль") $res.="<br/>Если вы давно зарегистрировались, то попробуйте использовать логин u_".substr($login,2).", а не ".substr($login,2);
                        if ($res && $res!="Данные не найдены") msg($res,"Ошибка");

                if ($res=="Данные не найдены") {        // новый персонаж
                        $stmp="Пол персонажа:
                        <br/><select name=\"sex\" value=\"m\">
                        <option value=\"m\">Муж.</option>
                        <option value=\"f\">Жен.</option>
                        </select>
                        Возраст:
                        <br/><input name=\"age\" format=\"*N\" maxlength=\"2\"/>
                        <br/><a href=\"$PHP_SELF?login=$login&p=$p&sex=$(sex)&age=$(age)&site=reg2\">Продолжить</a>";
                        msg($stmp,"Регистрация");
                        }

                $auser=unserialize($data);
                if (!$auser["char"] || !$auser["skills"]) {$data=preg_replace_callback('/s:(?:\d+):"(.*?)";/',function ($m) { return calcser($m[1]); },$data);$auser=unserialize($data);}
                $tuser=explode("|",$auser["user"]);
                $tp=$tuser[0];

        $res=GetGameTime(substr($login,2), $p, $t);
        if ($res=="Неверный пароль") {$login=""; msg("Неправильный пароль. Если вы сменили пароль, подождите 10 минут, проверьте список онлайн и войдите в игру заново.");}
        if ($res) msg($res,"Ошибка");
        if ($tuser[6]>$t) $tacc=$tuser[6]; else $tacc=$t;
        if ($_SERVER["REMOTE_ADDR"]!="62.118.250.193" && $login!=$g_admin && (!$zx || $zx!=$zero) && time()>$tacc) msg("Срок действия вашего аккаунта истек, вы можете заплатить (для абонентов моск. МТС и МЕГАФОН <a href=\"$PHP_SELF?site=accmts&sid=$login.$p\">бесплатно</a>) и продолжить игру.\n<br/><a href=\"http://mag.su/magmail/pay.php?login=".substr($login,2)."\">Оплатить аккаунт</a>\n<br/>Или обнулить персонажа (при этом потеряете весь накопленный опыт, навыки и предметы) и играть бесплатно еще три дня.<br/><a href=\"$PHP_SELF?site=accobn&login=$login&p=$p\">Обнулить персонажа</a><br/>Внимание! После обнуления персонаж восстановлению не подлежит!<br/><a href=\"$PHP_SELF\">На главную</a>","Территория Зла");

        $srvmain=implode("",file("srvmain.dat"));

        // проверим клан
	//print_r($auser);
        if (strpos($auser["char"],"*")===false) $clan=""; else $clan=substr($auser["char"],strpos($auser["char"],"*")+1,strrpos($auser["char"],"*")-strpos($auser["char"],"*")-1);
	//echo $clan;
	if ($clan && @implode("",@file($srvmain."f_common.php?inclan=$clan&login=$login"))=="no") $clan="no"; else $clan="yes";
                                 //  echo 1;
        // ок, получили данные, пробуем войти в игру

                header ("Expires: Thu, 01 Jan 1970 00:00:01 GMT");
                header ("Last-Modified: " . gmdate("D, d M Y H:i:s") . " GMT");
                header ("Cache-Control: no-cache, no-store, must-revalidate, max-age=0");
                header ("Pragma: no-cache");
                header ("Content-type:text/vnd.wap.wml;charset=utf-8");
                     // echo 1;
//echo "aa";
        ob_start("translate");
        if ($zx==$zero) $p.="&zx=".$zx;
        $tnews=@implode("",@file($srvmain."f_common.php?newstime=1"));
       // echo 1;
        if(strlen($tnews)>5) $tnews="";
        $s=@implode("",@file(@implode("",@file("serverurl.dat")).$srv."/g.php?site=connect2&sid=$login&login=$login&p=$p&f_c=$f_c&clan=$clan&tacc=$tacc&tnews=$tnews&ip=".$_SERVER["REMOTE_ADDR"]."&data=".urlencode($data)));
      //   echo 1;
        echo $s;
        ob_end_flush();
      //  echo 1;
        clearstatcache();
        if (filesize("online/".$login)==1 || strpos($s,"cnick=1")===false) @unlink("online/".$login);
        @ignore_user_abort(false);

        die("");


function calcser($s) {return "s:".strlen($s).":\"".$s."\";";}
function msg($s,$title_v="Территория Зла") {
        global $login;
                header ("Expires: Thu, 01 Jan 1970 00:00:01 GMT");
                header ("Last-Modified: " . gmdate("D, d M Y H:i:s") . " GMT");
                header ("Cache-Control: no-cache, no-store, must-revalidate, max-age=0");
                header ("Pragma: no-cache");
                header("Content-type:text/vnd.wap.wml;charset=utf-8");
        ob_start("translate");
        $s=str_replace("&amp;","&",$s);
        $s=str_replace("&","&amp;",$s);
        if (substr($s,0,2)!="<p") $s="<p>".$s;
        echo "<?xml version=\"1.0\"?>\n<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
        echo "
<wml>
<card title=\"$title_v\">";
echo "
$s
</p>
</card>
</wml>";
        ob_end_flush();
        clearstatcache();
        if (filesize("online/".$login)==1) @unlink("online/".$login);
        @ignore_user_abort(false);
        die("");
        }
