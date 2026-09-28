<?php

// Получаем GET параметр "m" - текст SMS от пользователя проекта - в переменную $_sInMsg

$_sInMsg = empty($_GET['m'])?"Пустая СМС":$_GET['m'];

// Получаем GET параметр "s" - короткий номер, на который пользователь отправил SMS - в переменную $_sInSrv

$_sInSrv = empty($_GET['s'])?"Пустой номер":$_GET['s'];

// Программный код обработки входящего SMS сообщения от пользователя проекта

//
 function returnkn($kn)
 {
 if($kn=='1313') {$addgold='4000';}
 if($kn=='1415') {$addgold='7500';}
 if($kn=='1314') {$addgold='10000';}
 if($kn=='1220') {$addgold='20000';}
 if($kn=='1330') {$addgold='25000';}
 if($kn=='1440') {$addgold='30000';}
 if($kn=='1450') {$addgold='30000';}
 if($kn=='1515') {$addgold='40000';}
 if($kn=='2525') {$addgold='40000';}
 if($kn=='1715') {$addgold='50000';}
 if($kn=='1815') {$addgold='50000';}
 if($kn=='1315') {$addgold='4000';}
 if($kn=='1340') {$addgold='10000';}
 if($kn=='1230') {$addgold='20000';}
 if($kn=='1420') {$addgold='30000';}
 if($kn=='1517') {$addgold='40000';}
 if($kn=='4850') {$addgold='15000';}
 if($kn=='4024') {$addgold='20000';}
 if($kn=='4042') {$addgold='30000';}
 if($kn=='4004') {$addgold='35000';}
 return $addgold;
 }
if($_sInMsg) {
 $all = @file ("./playfon/u.".strtolower($_sInMsg));
 $fil=legacy_num(returnkn($_sInSrv))+legacy_num($all[0]);
 $file = @legacy_fopen_w ("./playfon/u.".strtolower($_sInMsg), "w");
 legacy_fwrite($file,$fil);legacy_fclose($file);
} else $_sOutMsg='Error';
// здесь добавьте программный код обработки SMS от пользователей, присущий Вашему проекту

// далее предполагаем, что после обработки текст ответного SMS будет в переменной $_sOutMsg

//

// Предварительная обработка текста ответного SMS для пользователя (преобразование символов сообщения в соответствующие HTML сущности)

$_sOutMsg = htmlentities($_sOutMsg);

// Упаковка ответного сообщения для пользователя в требуемый формат (см. Технические требования к проекту) 

$_sOut = sprintf('{"message":"%s","status":true}',$_sOutMsg);

// Возврат сообщения для пользователя в качестве ответа WEB-сервиса на HTTP-GET запрос

print($_sOut);

?>