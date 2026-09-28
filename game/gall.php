<?php
require("config.ssp");
$link = @mysql_pconnect ($server, $user, $dbpass)
                or die ("Can not connect to MySQL");

     @mysql_select_db($dbname) or die ("error select the database...");

$dir_games = 'photos';       // папка с фотками юзера




$title_games = 'вход в галерею';      // заголовок + название ссылки на главной странице
$title =       'ФОТО-ГАЛЕРЕЯ ИГРЫ';   // заголовок  на главной странице
$five_games = 10;            // кол-во ссылок на странице с играми


/******* ДАЛЬШЕ НЕ МЕНЯТЬ ********/

$DIR   = FALSE;
$NEMO  = FALSE;
$URL   = $_SERVER['PHP_SELF'];

$d = empty($_GET['d'])?'index':$_GET['d'];
$p =  empty($_GET['p'])?0:$_GET['p'];

if($d == $dir_games) {$title = $title_games;    $DIR = $dir_games;}


        function DIR_ARRAY($DIR)
        {

        GLOBAL $dir_games;

        if(is_dir("./$DIR"))
        {
        if ($dir = opendir("./$DIR"))
        {
        while (false !== ($file = readdir($dir)))
        {
        while (false !== ($file = readdir($dir)))
        {

                if($DIR == $dir_games) {if (!preg_match("/^(.*)\\.(jpg|JPG|gif|GIF|png|PNG|wbmp|WBMP)$/",$file))continue; $array_files[]=$file;}
        }
        }
        closedir($dir);
        }
        return !empty($array_files)?$array_files:FALSE;
        }

        }

header("Content-type: text/vnd.wap.wml;charset=utf-8");
$ret .= '<?xml version="1.0" encoding="utf-8"?><!DOCTYPE wml PUBLIC "-//WAPFORUM//DTD WML 1.1//EN" "http://www.wapforum.org/DTD/wml_1.1.xml">';
$ret .= '<wml><card title="'.$title.'"><p>';

        $TITLE_LIST = '';

        if(!empty($dir_games)) $TITLE_LIST .= '<a href = "'.$URL.'?d='.$dir_games.'">'.$title_games.'</a><br />';


        if(!$DIR)
        $ret .= $TITLE_LIST;
        else
        {
        $array = DIR_ARRAY($DIR);
        $count = sizeof((array)($array));
        
        if($DIR == $dir_games && !empty($dir_games))
        {

        $ret_games = '';

        if(!$array) $ret_games = 'извините, у нас пока нет фоток';

        $FIN =  ($p1+1) *   $five_games;
        $BEG =  $FIN - $five_games;

        for($i = $BEG; $i < $FIN; $i++)
        {
        if(!empty($array[$i]))
        {
        $name_file = substr($array[$i],0,-4);
		  $result = mysql_query ("select * from users where id = '".$name_file."'");
    $inf=mysql_fetch_array ($result);
        $ret_games .= '<a href="'.$dir_games.'/'.$array[$i].'">'.$inf['nick'].'</a><br/>';
        $NEMO = TRUE;
        }
        }

        if($NEMO)
        {
        if($p1 > 0)                         $ret_games .='<br/><a href="'.$URL.'?d='.$DIR.'&amp;p1='.($p1-1).'">[назад]</a>';
                                           $ret_games .='<br/>';
        if(($p1+1)*$five_games <= $count-1) $ret_games .='<a href="'.$URL.'?d='.$DIR.'&amp;p1='.($p1+1).'">[далее]</a><br/>';
                                           $ret_games .='<a href="http://bigwap.org">[на главную]</a>';
        }
        else    $ret_games = 'извините, этого пользователя у нас нет';
        $ret .= $ret_games;
        }
                else    $ret .= $TITLE_LIST;
}
$ret .= '</p></card></wml>';

function unicode($string)
{
$rus=array('А','Б','В','Г','Д','Е','Ж','З','И','Й','К','Л','М','Н','О','П','Р','С','Т','У','Ф','Х','Ц','Ч','Ш','Щ','Ъ','Ы','Ь','Э','Ю','Я',
           'а','б','в','г','д','е','ж','з','и','й','к','л','м','н','о','п','р','с','т','у','ф','х','ц','ч','ш','щ','ъ','ы','ь','э','ю','я');
$string = str_replace('Ё','&#1025;',$string);
$string = str_replace('ё','&#1105;',$string);
for($i=0; $i<count((array)($rus)); $i++)
   {
   $s=1040;
   $d = '&#'.($s+$i).';';
   $string = str_replace($rus[$i],$d,$string);
   }
return $string;
}

echo unicode($ret);
@mysql_close();
?> 