<?php 

$gamedays = 27;  // Оплачиваемый период
$freedays = 3000; // Бесплатно дней можно играть

/*

Blade: 01.06.2004: В функцию SetData() и checkpass() внесены изменения, позволяющие записывать игровые данные без пароля (чтобы убрать возможность клонирования вещей)
Blade: 16.09.2004: скопировал сюда функцию SetPartner()
Blade: 18.01.2005: изменил GetData() с учетом сервера
Blade: 20.04.2005: ввел проверку на вход только с одного сервера
Blade: 21.04.2005: убрал проверки, т.к. изменил f_connect.php

Добавились 2 функции и 2 переменные (см.выше)

function GetGameTime($login, $pass, &$time)
{ // Возвращает пустую строку в случае успеха или сообщение об ошибке.
  //Возвращает время (в $time) окончания оплаченного периода или 0 - если оплаты не было

function SetGameTime($login, $pass, $time)
{ // Возвращает пустую строку в случае успеха или сообщение об ошибке.
  // Запрещает устанавливать $time > (time() + ($gamedays+$freedays)*24*3600)
*/

/*
3 функции: SetData, GetData, SetUser

function SetData($login,$pass,$data)
{ // Возвращает пустую строку в случае успеха или сообщение об ошибке.

function GetData($login,$pass,&$data)
{ // Возвращает пустую строку в случае успеха (данные возвращаются в $data) или сообщение об ошибке.

function SetUser($login,$oldpass,$newpass)
{//  Регистрация нового пользователя (oldpass = "") или смена пароля.
 // Возвращает пустую строку в случае успеха или сообщение об ошибке.

При авторизации login и pass - не более 10 символов, а также огрничения в синтаксисе (только
латинские буквы и знак подчёркивания)
При обращении к функциям авторизации стоит 30-секундная защита от подбора пароля.

*/

$NOT_SET = "NOT_SET";
require("config.ssp"); // Настройки БД
//require($GLOBALS["DOCUMENT_ROOT"]."/antimat.ssp"); // Антимат-фильтр ( чтобы ники матерные не регистрировали)


function ValidNN($s)
{
  return !preg_match("/[^a-z0-9_]/i",$s);
}

function ValidPass($s)
{
  return !preg_match("/[^a-z0-9]/i",$s);
}

function InitParam($N,$V)
{
global $Names,$Values;
  $Names = $N;
  $Values = $V;
}

function GetParam($Name)
{
global $Names,$Values,$NOT_SET;
  $Name = strtolower($Name);
  $Nlist = explode(":",$Names);
  for ($i=0; $i<count((array)($Nlist)); $i++) if ($Nlist[$i] == $Name) break;
  if ($i == count((array)($Nlist))) return $NOT_SET;
  $Vlist = explode(":",$Values);
  return stripslashes(str_replace("!~!",":",$Vlist[$i]));
}


function SetParam($Name,$Value)
{
global $Names,$Values,$NOT_SET;
  $Nlist = explode(":",$Names);
  $Name = strtolower($Name);
  $Value = addslashes(str_replace(":","!~!",$Value));
  for ($i=0; $i<count((array)($Nlist)); $i++) if ($Nlist[$i] == $Name) break;

  if ($i == count((array)($Nlist)) and ($Value != $NOT_SET))
  { // Добавляем имя и значение
    $Names .= ":$Name";
    $Values .= ":$Value";
  }
  else
  {
    $Vlist = explode(":",$Values);
    $Vlist[$i] = $Value;
    $Values = implode(":",(array)($Vlist));
    if ($Value == $NOT_SET)
    { // Удаление имени и значения
      $Nlist[$i] = $NOT_SET;
      $Names = implode(":",(array)($Nlist));
      $Names = str_replace(":$NOT_SET","",$Names);
      $Values = str_replace(":$NOT_SET","",$Values);
    }
  }
}


function checkpass($nick, $pass, $fields, &$result,$skippass=0)
{ //
global $PassDelay;
  if ($fields == "") $fields = "pass,lastrefr";
  else if ($fields !== "*") $fields .= ",pass,lastrefr";

  $now = time();
  $sql="select $fields from users where nick='$nick'";
  $result=mysql_query($sql) or die(mysql_error());
  if (mysql_num_rows($result)!=1) return "Логин не найден";

  $row = mysql_fetch_array($result);
  $dt = $PassDelay - $now + $row['lastrefr'];
  if ($dt > 0) return "Повторите через $dt"."sec";

  if ($row['pass']!=$pass && !$skippass)
  {
    $sql = "update users set lastrefr=$now where nick='$nick'";
    mysql_query($sql) or die(mysql_error());
    return "Неверный пароль";
  }
  return "";
}

function openDB()
{
global $server,$user,$dbpass,$dbname;
  $sesDB = @mysql_connect($server,$user,$dbpass);
mysql_query("SET NAMES 'cp1251'"); 
mysql_query("SET CHARACTER SET 'cp1251'"); 
mysql_query("SET collation_connection=cp1251_general_ci"); 
mysql_query("SET character_set_results=cp1251"); 
mysql_query("SET character_set_client=cp1251"); 
mysql_query("SET collation_database=cp1251_general_ci");
  if (!$sesDB) return "База данных недоступна. Повторите через 5мин";
  $ok = @mysql_select_db($dbname,$sesDB);
  if (!$ok)return "База данных недоступна. Повторите через 5мин";
  return "";
}

function SetData($login,$pass,$data)
{ // Возвращает пустую строку в случае успеха или сообщение об ошибке.
global $error, $Names, $Values;
  if (empty($login)) return "Логин не задан";
  if (empty($pass)) return "Пароль не задан";

  $maxdata = 5000; // Максимальная длина данных
  if(strlen($data)>$maxdata) return "Слишком длинная строка.";

  $error =openDB();
  if ($error != "") return $error;

  $ok = checkpass($login,$pass,"names,vals",$result,1);        // сохраняет без пароля!
  if ($ok != "") return $ok;
  InitParam(mysql_result($result,0, "names"),mysql_result($result,0, "vals"));

  SetParam('gamedata',$data);

  $sqlUpd = "update users set names='$Names', vals='$Values' where nick = '$login'";
  mysql_query($sqlUpd) or die(mysql_error());
  mysql_close();
}

function GetData($login,$pass,&$data,$srv=0)
{ // Возвращает пустую строку в случае успеха (данные возвращаются в $data) или сообщение об ошибке.
global $error, $Names, $Values, $NOT_SET;
  if (empty($login)) return "Логин не задан";
  if (empty($pass)) return "Пароль не задан";


  $error =openDB();
  if ($error != "") return $error;

  $ok = checkpass($login,$pass,"names,vals",$result);
  if ($ok != "") return $ok;
  InitParam(mysql_result($result,0, "names"),mysql_result($result,0, "vals"));
  mysql_close();
  $data = GetParam("gamedata");
  if ($data == $NOT_SET) return "Данные не найдены";

/*
  // проверим сервер
  if ($srv>0) {
        $tus=unserialize($data);
        if (gettype($tus)!="array") return "";
        if ($tus["srv"]>0 && $srv!=$tus["srv"]) return "Чтобы играть на сервере ".$srv.", вначале зайдите на сервер ".$tus["srv"]." и сохранитесь на нем (персонаж должен покинуть игру), либо выберите Опции аккаунта и Сменить сервер.";
        }
*/
  return "";
}

function SetUser($login,$oldpass,$newpass)
{ // Регистрация нового пользователя (oldpass = "") или смена пароля.
  // Возвращает пустую строку в случае успеха или сообщение об ошибке.
global $RegStatus,$DefRefrInt,$DefMessLim,$CommonMode;

  if (empty($login)) return "Логин не задан";
  if (empty($newpass)) return "Пароль не задан";
  if (!ValidNN($login)) return "Неверный синтаксис в логине";
  if (!ValidPass($newpass)) return "Неверный синтаксис в пароле";

  $login=substr($login,0,10);
  $newpass=substr($newpass,0,10);

  $BadWord = GetBadWord($login);
  if ($BadWord != "") return "Логин содержит запрещённое слово";

  $error =openDB();
  if ($error != "") return $error;

  if ($oldpass !="")
  {
    $ok = checkpass($login,$oldpass,"",$result);
    if ($ok != "") return $ok;
    $sqlUpd = "update users set pass='$newpass' where nick = '$login' and pass='$oldpass'";
    mysql_query($sqlUpd) or die(mysql_error());
  }
  else
  {
    $sqlSel="select * from users where nick = '$login'";
    $result=mysql_query($sqlSel) or die(mysql_error());
    $Count=mysql_num_rows($result);
    if ($Count != 0) return "Такой логин уже зарегистирован";
    $now = time();
    $sqllogin ="insert into users (status,sent,regtime,refrint,messlim,mode,nick,pass) values ('$RegStatus','0', '$now', '$DefRefrInt','$DefMessLim', '$CommonMode','$login', '$newpass')";
    mysql_query($sqllogin) or die(mysql_error());
  }
  mysql_close();
  return "";
}

function GetGameTime($login, $pass, &$time)
{ // Возвращает пустую строку в случае успеха или сообщение об ошибке.
  //Возвращает время (в $time) окончания оплаченного периода или 0 - если оплаты не было
global $error;
  if (empty($login)) return "Логин не задан";
  if (empty($pass)) return "Пароль не задан";

  openDB();
  if ($error != "") return $error;

  $ok = checkpass($login,$pass,"gametime",$result);
  if ($ok != "") return $ok;
  $time = mysql_result($result,0, "gametime");
  mysql_close();
  return "";
}

function SetGameTime($login, $pass, $time)
{ // Возвращает пустую строку в случае успеха или сообщение об ошибке.
  // Запрещает устанавливать $time > (time() + ($gamedays+$freedays)*24*3600)
global $error, $gamedays, $freedays;
  if (empty($login)) return "Логин не задан";
  if (empty($pass)) return "Пароль не задан";

  openDB();
  if ($error != "") return $error;

  $ok = checkpass($login,$pass,"",$result);
  if ($ok != "") return $ok;

  if ($time > (time() + ($gamedays+$freedays)*24*3600)) return "Период оплаты превышает ".($gamedays+$freedays)." дней";
  $sqlUpd = "update users set gametime='$time' where nick = '$login' and pass='$pass'";
  mysql_query($sqlUpd) or die(mysql_error());
  mysql_close();
  return "";
}



function SetPartner($login, $pi)
{ // Возвращает пустую строку в случае успеха или сообщение об ошибке.
  // Фиксирует партнёра для ника (login)
  // Внимание - не проводится проверка на наличие login-а
global $error;
  openDB();
  if ($error != "") return $error;
  $sqlUpd = "update users set pi='$pi' where nick = '$login'";
  mysql_query($sqlUpd) or die(mysql_error());

  $now = time();
  $sqlIns ="insert into partners (time,nick,pi) values ('$now','$login','$pi')";
  mysql_query($sqlIns) or die(mysql_error());
  mysql_close();
  return "";
}



?>
