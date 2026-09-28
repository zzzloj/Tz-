<?php
error_reporting(0);
error_reporting(0);
extract($_GET);
  extract($_POST);
  extract($_COOKIE);
  extract(isset($_SESSION) ? $_SESSION : array());
  extract($_SERVER);
// возвращает -1 если есть $login в папке online
$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);
if ($newstime) die(@date("d/m",legacy_num(@filemtime("story/news.htm"))));

// список у Санчеса
if ($gwanted) die(@implode("",(array)(@file("wanted.dat"))));
if ($swanted) {
        $file = legacy_fopen_w ("wanted.dat", "w");
        if($file!==false) {legacy_fputs($file,str_replace("\\","",$swanted));legacy_fclose($file);}
        }

// кланы

if ($gclan) {if(!file_exists("clans/".$gclan)) die("none"); else die(@implode("",(array)(@file("clans/".$gclan))));}
if ($inclan) {
                $dh = opendir("clans");
                while (($fname = readdir($dh))!== false) if ($fname!="." && $fname!=".." && $fname!="1.htaccess" && $fname!=".htaccess") {
                        if(strtolower($fname)==strtolower($inclan)) {
                                closedir($dh);
                                $tmp=@unserialize(@implode("",(array)(@file("clans/".$fname))));
                                if (gettype($tmp)=="array" && !isset($tmp["m"][$login]) && !isset($tmp["v"][$login]) && !isset($tmp["s"][$login]) && !isset($tmp["g"][$login])) die("no"); else die("yes");
                                }
                        }
                closedir($dh);
                die("no");
        }
if ($sclan) {
        $test=unserialize(str_replace("\\","",$data));
        if ($test["g"]) {
                $file = legacy_fopen_w ("clans/".$sclan, "w");
                if($file!==false) {legacy_fputs($file,str_replace("\\","",$data));legacy_fclose($file);}
                } else die("err:");
        }
if ($dclan) {unlink("clans/".$dclan); die("ok:");}
if ($eclan) {
                $dh = opendir("clans");
                while (($fname = readdir($dh))!== false) if ($fname!="." && $fname!=".." && $fname!="1.htaccess" && $fname!=".htaccess") {
                        if(strtolower($fname)==strtolower($eclan)) {closedir($dh); die("yes");}
                        }
                closedir($dh);
                die("no");
                }
if ($payed) {
        if (file_exists("payed.dat")) {
                $all=0;
                $all=@implode("",(array)(@file("payed.dat")));
                if ($all) {$all++;$file=legacy_fopen_w("payed.dat","w");if($file!==false){legacy_fputs($file,$all);legacy_fclose($file);}}
                 } else {$file=legacy_fopen_w("payed.dat","w");if($file!==false){legacy_fputs($file,"1");legacy_fclose($file);}}
        die("ok");
        }
if ($reg2) {
        if (file_exists("all.dat")) {
                $all=0;
                $all=@implode("",(array)(@file("all.dat")));
                if ($all) {$all++;$file=legacy_fopen_w("all.dat","w");if($file!==false){legacy_fputs($file,$all);legacy_fclose($file);}}
                 } else {$file=legacy_fopen_w("all.dat","w");if($file!==false){legacy_fputs($file,"1");legacy_fclose($file);}}
        die("ok");
        }
