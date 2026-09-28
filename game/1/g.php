<?php
//error_reporting(0);
extract($_GET);
extract($_POST);
extract($_COOKIE);
extract($_SERVER);
//extract(isset($_SESSION) ? $_SESSION : array());

session_name('SID');
if (!$_SESSION["traf"]) { session_start(); }
	//получяем ip
	$ip = ("$REMOTE_ADDR");
	//получяем браузер
	$brouzer=$_SERVER["HTTP_USER_AGENT"];
	
include('gzip.php');
$tmp=$QUERY_STRING;if($tmp=='')$tmp=$_SERVER['QUERY_STRING'];
$tmp=urldecode($tmp);
$tmp=preg_replace("/[^ -}А-я]/","",$tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);
$g_tmp=$tmp;
$PHP_SELF='g.php';
//$serv=rand(1,3);
//if($serv==1) $PHP_SELF='g.php'; elseif($serv==2) $PHP_SELF='d.php'; else $PHP_SELF='h.php';
$g_max=100;
$ver='1.9';
$g_admin='u.admin';
$zero='mda';
$gg_list=5;
$g_list=10;
$g_size=2200;
$g_menu=0;
$g_smenu='301020';
$g_j2loc=1;
$g_j2go=1;
$g_joff=0;
$g_smf=0;
$g_map=3;
$g_sounds=0;
$g_logout = 600;
$g_destroy = 600;
$g_crim = 1800;
$g_exp = 10;
$g_attr = 12;
$g_attr_one=5;
$g_skills=50;
$g_skills_one=5;
$g_ch=0;
$l_i=array();
$l_t=array();
$l_tt=array();

if ($debug==$zero || $zxf==$zero) $debug=1; else $debug=0;
if ($sid && substr($sid,0,2)!='u.') $sid='u.'.$sid;
if ((!$sid && !$site) || !$tmp) $site='main';
$glast=@implode('',(array)(@file('glast.dat')));
$glast=trim($glast); //$sl=rand(3,20); sleep($sl);
$tlast=time().$_SERVER['HTTP_USER_AGENT'].substr($sid,0,10);
$file=fopen('glast.dat','w');@fputs($file,$tlast);@fclose($file);
if ($glast==$tlast) msg("<a href=\"$PHP_SELF?$g_tmp\">Обновить</a>");

if ($sid) {
        $ts=explode('.',$sid);
        $tl='u.'.strtolower($ts[1]);
        //if (file_exists('online/'.$tl)) {$tmp=file('online/'.$tl); $tmp[1]=trim($tmp[1]); if (time()<$tmp[1]) $tmp[1]=0; if (time()-$tmp[1]<3) sleep(3-(time()-$tmp[1]));}//скорость обновления && $ip!="87.118.109.33" && $ip!="83.239.237.106"
        }

$t_g = sscanf(microtime(),"%s %s");
$t_g = $t_g[1]+$t_g[0];
if (!$mod && !$adm && $REMOTE_ADDR!='127.0.0.1' && $REMOTE_ADDR!='87.118.104.111') {
if(preg_match("/[\/{}$\"\']+/",$QUERY_STRING)) msg('error');
}
//if (!$mod && !$adm && $REMOTE_ADDR!="127.0.0.1" && $REMOTE_ADDR!="80.91.191.171") {
//if(preg_match("/[\/{}$\"\']+/",$_POST)) msg("error");
//}
if (file_exists('game.dat')) {
        $file_save = fopen('game.dat','r+');
        if (!$file_save) msg('Ошибка загрузки game.dat');
        if (flock($file_save,2)) {
                rewind($file_save);
                $game = fread($file_save, 65535);
                $game = unserialize($game);
                if (gettype($game)!='array') $game=array();
                } else {$file_save=''; msg('Ошибка блокировки game.dat');}
        } else {$file_save = fopen('game.dat','w+'); if ($file_save && flock($file_save,2)) {$f_all=1; require 'f_online.dat'; require 'f_blank.dat';} else {$file_save=''; msg('Ошибка создания game.dat');}}


srand ((float) microtime() * 10000000);
//if ($zxf==$zero) require 'f_zxf.dat';
if ($game['stop']) require 'f_stop.dat';

     unset($pi);
if ($game['msg'] && $zx!=$zero) msg($game['msg']);

if ($site) require "f_site_".preg_replace('/\W/',"",$site).".dat";

if (time()>$game['lastai']+240) require 'f_online.dat';
$sid=explode('.',$sid);
$login='u.'.strtolower($sid[1]);
if(!$p) $p=$sid[2];
$sid=substr($login,2).'.'.$p.'.'.chr(rand(97,122));
if ($_GET['zx']==$zero) $sid.='&zx='.$zero;
//if ($gm || $zx || $adm || strpos($QUERY_STRING,'gm_id')!==false || $cm=='save') { @mail("admin@bigwap.org", "CHEAT ADM: ".$login, $QUERY_STRING, "From: chit@bigwap.org"); }
if ($ip=='217.144.164.164' || $login=='u.alatiel' || $login=='u.aiatiel' || $login=='u.fox' || $login=='u.liska' || $login=='u.anjelika' || $login=='u.erra' || ($brouzer=='SonyEricssonK500i/R2AA SEMC-Browser/4.0.2 Profile/MIDP-2.0 Configuration/CLDC-1.1' && $ip=='83.149.48.211')) { header ("Location: http://mag.su"); exit();}
//if ($debug) $sid.='&debug='.$zero;
if (!file_exists('online/'.$login)) {$f_c=1;require 'f_site_connect2.dat';}
$tmp=file('online/'.$login);
$loc=trim($tmp[0]);
loadloc($loc);
if (isset($l_i[$loc]["u.alatiel"])) {
$log_save=$login." - ".$loc." - ".$QUERY_STRING." - ".date("d/m Y H:i",time())."\r\n";
$file=fopen("./z_net_dannih.txt","a+");@fputs($file,$log_save);@fclose($file);
@unlink('online/'.$login); msg('Нет данных');}
if ($p!=substr($l_i[$loc][$login]["user"],0,strpos($l_i[$loc][$login]["user"],"|"))) require("f_npass.dat");
if ($l_i[$loc][$login]["o"]) list($g_list,$g_size,$g_j2loc,$g_j2go,$g_menu,$g_sounds,$g_joff,$g_smenu,$g_map,$g_smf,$g_ch)=explode("|",$l_i[$loc][$login]["o"]);
if ($cnick) require "f_cnick.dat";
if ($go) {
        if ($loc=="x927x253" && $go=="x902x254") msg('Стражник: Стой!');
        if ($go=="x1746x545" && strpos($l_i[$loc][$login]["items"],"i.q.keykrep")===false) msg("Без ключа не пройти");
      if ($loc=="x216x1099" && $go=="x138x1380") msg("между вами и входом в шахту встает грозный гном, с гиганским двухсторонним топором, на плече. Явно запрещая вам пройти в нутрь");
      if ($loc=="x233x2330" && $go=="_begi" && strpos($l_i[$loc][$login]["items"],"i.q.keykrep1")===false) msg("А вы симпотично смотритесь ;)");
      if ($loc=="x154x1540" && $go=="x155x1540") msg("Ангел сверхестественной силой не дает вам двигаться дальше");

        $loc_c = explode("|",$l_tt[$loc]["d"]);
        $b=array_search($go,(array)($loc_c));
        if ($b) {
                $tgo=$loc_c[$b-1];
                loadloc($go);
                $b=0;
                $char = explode("|",$l_i[$loc][$login]["char"]);
            	$skills = explode("|",$l_i[$loc][$login]["skills"]);
                if (rand(1,100)<=$skills[17]*8) $hide=1; else $hide=0;
                if ($gal && $char[12]) {$loc_c = explode("|",$l_tt[$go]["d"]); $b=array_search($tgo,(array)($loc_c));}
                if (!$b) $tgo="";
                addnpc($login,$loc,$go,$tgo,$hide);
                if ($b) addnpc($login,$loc,$loc_c[$b+1],1,$hide);
                }
        }
		if($login=='u.tony' || $login=='johnson22' || $login=='u.georg2') {
	$log_save=$login." - ".$loc." - ".$QUERY_STRING." - ".date("d/m Y H:i",time())."\r\n";
$file=fopen("./z_net_dannih.txt","a+");@fputs($file,$log_save);@fclose($file);	
		}
if ($game['fid']==$login) $game['floc']=$loc;
if (!$game['floc'] || isset($l_tt[$game['floc']]) && !isset($l_i[$game['floc']]['i.flag']) && !isset($l_i[$game['floc']][$game['fid']])) {$l_i[$loc]['i.flag']='флаг лидерства|1|0'; $game['floc']=$loc;$game['fid']='';}
if ($ctele) require 'f_castle.dat';
if ($stele) require 'f_stele.dat';

$loc_c = explode('|',$l_tt[$loc]['d']);
for($i=2;$i<count((array)($loc_c));$i+=2) loadloc($loc_c[$i+1]);
doai($loc);
for($i=2;$i<count((array)($loc_c));$i+=2) doai($loc_c[$i+1]);

if (!isset($l_i[$loc][$login]) || !$login) {
$log_save=$login.' - '.$loc.' - '.$QUERY_STRING.' - '.date('d/m Y H:i',time()).'\r\n';
$file=fopen('./z_net_dannih.txt','a+');@fputs($file,$log_save);@fclose($file);
@unlink('online/'.$login); msg('Нет данных');}
$char = explode('|',$l_i[$loc][$login]['char']);

if ($ce) require 'f_logout.dat';
$ip2=$l_i[$loc][$login]['ip'];
if ($ip2 && $ip2!=$ip1 && $ip2!=$ip && $zx!=$zero) require('f_nip.dat');

// подгружаемые модули
if ($cm) if ($cm>0 && $cm<9) {
        $cm--;
        $x_macros=@explode('/',$l_i[$loc][$login]['macro']);
        $x_macros=@explode('|',$x_macros[$cm]);
        $x_macrosl=@explode('|',$l_i[$loc][$login]['macrol']);
        if ($x_macros[0]=='last' && $x_macrosl[0]) eval('$'.$x_macrosl[0]."=\"".$x_macrosl[1]."\";"); else if ($x_macros[0]) eval('$'.$x_macros[0]."=\"".$x_macros[1]."\";");
        if ($x_macros[2]=='last' && $x_macros[0]=='ca') $ca=$x_macrosl[1]; else
        if ($x_macros[2]=='last' && $x_macrosl[2]) eval('$'.$x_macrosl[2]."=\"".$x_macrosl[3]."\";"); else if ($x_macros[2]) eval('$'.$x_macros[2]."=\"".$x_macros[3]."\";");
        } else require 'f_macro.dat';
$zero='mda';
if ($adm && file_exists('f_admin.dat')) require 'f_admin.dat';
if ($mod && file_exists('f_mod.dat')) if ($login=='u.admin' || $login=='u.scream' || $login=='u.sn0k' || $login=='u.wildspb' || $login=='u.ps_one' || $login=='u.qv' || $login=='u.qw' || $login=='u.kv' || $login=='u.scream') require 'f_mod.dat';

if ($speak || $speak=$cs) if (substr($speak,0,2)=='i.') $take=$speak; else require 'f_speak.dat';
if ($take) require 'f_take.dat';
if ($say) require 'f_say.dat';
if ($ca) {$l_i[$loc][$login]['macrol']="ca|$ca||"; $char[7]=$ca; $l_i[$loc][$login]['char']=implode('|',(array)($char)); attack($loc,$login,$ca); $char = explode('|',$l_i[$loc][$login]['char']);}
if ($drop) require 'f_drop.dat';
if ($dn) require 'd.dat';
if ($blogid) require 'c.dat';
if ($use) {
        $l_i[$loc][$login]['macrol']="use|$use|to|$to";
        if ($char[6]-time()>120) $char[6]=time()-1;
        if (time()>$char[6]) {
                if ($char[8] && $login!='u.qv' && $login!='u.wildspb' && $login!='u.ps_one' && $login!='u.sn0k' && $login!='u.qw' && $login!='u.kv' && $login!='u.scream') msg('<p>Вы призрак, найдите лекаря или камень воскрешения');
                if (substr($use,0,6)=='i.note' || substr($use,0,6)=='i.book') $look=$use;
                        else {
                                $scroll = 0;         // со свитка
                                if (substr($use,0,2)=='i.')  require 'f_useitem.dat';
                                else if (substr($use,0,2)=='m.') require 'f_usemagic.dat';
                                else if (substr($use,0,2)=='p.') require 'f_usepriem.dat';
                                else if (substr($use,0,6)=='skill.') require 'f_useskill.dat';
                                $char = explode('|',$l_i[$loc][$login]['char']);
                                }
                } else addjournal($loc,$login,'Вы должны отдохнуть '.round($char[6]-time()+1).' сек');
        }                // раньше $list
if ($look || $look=$ci) require 'f_look.dat';                // после $take и $use
if ($msg) require 'f_msg.dat';
if ($trade) require 'f_trade.dat';
if ($cl=='i') $cl='inv';
if ($cl=='m') $cl='magic';
if ($cl=='p') $cl='priem';
if ($cl=='e') $cl='eq';
if ($cl=='r') $cl='er';
if ($cl=='v') $cl='ev';
if ($cl=='par') $cl='par';
if ($list || $list=$cl) require 'f_list'.$list.'.dat';
if ($map) require 'f_map.dat';

// MAIN PAGE
$stmp='';

			$inf[1]=$ip;
			$inf[0]=$brouzer;
			$inf[0]=htmlspecialchars(stripslashes($inf[0]));
			$inf[1]=htmlspecialchars(stripslashes($inf[1]));
			$l_i[$loc][$login]["inf"]=implode(":||:",(array)($inf));

if ($l_i[$loc][$login]['msgt']) $stmp.="<a href=\"$PHP_SELF?sid=$sid&msg=1\">[л/с].</a><br/>";
$stmp.=$char[1].'/'.$char[2].' ('.$char[3].'/'.$char[4].')';
$st='';
if ($char[12]) {$st.=' всадник'; }
if ($char[8]) $st.= ' призрак';
if (time()<$char[17]) $st.= ' отравлен';
if ($char[9]) $st.= ' '.$char[9].' ('.(round(($char[10]-time())/60)+1).' мин)';
if ($game['fid']==$login) $st.=' c флагом!';
if ($st) $stmp.=', вы '.$st;
if ($l_i[$loc][$login]['def']) {$tdef=explode('|',$l_i[$loc][$login]['def']); if(time()>$tdef[2]) $l_i[$loc][$login]['def']=''; else $stmp.='<br/>'.$tdef[1].' ('.($tdef[2]-time()).' сек)';}
if (substr($loc,3)=='.in' || substr($loc,3)=='.gate') require 'f_castle.dat';

// SOUNDS
if (!$g_sounds) {
        $st='';
        for ($i=2;$i<count((array)($loc_c));$i+=2) if ($loc_c[$i+1]!=$loc){
                if (count((array)($l_i[$loc_c[$i+1]]))>0) {
                        foreach($l_i[$loc_c[$i+1]] as $j=>$val) if ((substr($j,0,2)=='u.') || substr($j,0,2)=='n.') {if ($st=='') $st='<br/>Звуки: '.$loc_c[$i]; else $st.=', '.$loc_c[$i]; break;}
                        }
                }
        $stmp.= $st;
        }

// OBJECTS
$ti=explode('x',$loc);
if (!$start) $start=0;
$keys=array_keys((array)($l_i[$loc]));
for ($i=$start;$i<$start+$g_list && $i<count((array)($keys));$i++) if ($keys[$i]!=$login && $l_i[$loc][$keys[$i]]['god']!='1')  {  //режим невидимки
        if (substr($keys[$i],0,2)=='i.') {
                $tmp=explode('|',$l_i[$loc][$keys[$i]]);
                $k=$tmp[0];
                if (strpos($keys[$i],'..')!==false) $k.=' *';
                if (substr($keys[$i],0,4)!='i.s.' && $tmp[1]>1) $k.=' ('.$tmp[1].')';
                }
        if (substr($keys[$i],0,2)=='n.' || substr($keys[$i],0,2)=='u.') {
                $tmp=explode('|',$l_i[$loc][$keys[$i]]['char']);
                $k=$tmp[0];
                if (substr($keys[$i],0,2)=='u.' && $tmp[12]) {$k.=' (всадник)'; }
                $st='';
                if ($tmp[1]!=$tmp[2]) if (round($tmp[1]*100/$tmp[2])<100) $st.=round($tmp[1]*100/$tmp[2]).'%';
                if ($game['floc']==$loc && $game['fid']==$keys[$i]) $st.=' с флагом!';
                if (substr($keys[$i],0,2)=='u.') {
                        if ($tmp[8]) $st.=' призрак';
                        if ($ti[2]>=1099 && $ti[2]<=1370 && $tmp[14]=='t') $st.=' тамплиер';
                        if ($ti[2]>=1099 && $ti[2]<=1370 && $tmp[14]=='p') $st.=' пират';
                        }
                if ($tmp[9]) $st.=' '.$tmp[9];
                if ($tmp[7] && isset($l_i[$loc][$tmp[7]]) && (substr($keys[$i],0,2)=='n.' || substr($keys[$i],0,2)=='u.' && $loc_c[1]!=1)) {
                        $tmp1=explode('|',$l_i[$loc][$tmp[7]]['char']);
                        if (substr($tmp[7],0,2)=='n.' || (substr($tmp[7],0,2)=='u.' && !$tmp1[8])) {$st.=' атакует '; if ($tmp[7]==$login) $st.='вас!'; else $st.=preg_replace("/ \*.*?\*/","",$tmp1[0]);}
                        }
                if ($st) $k.=' ['.trim($st).']';
                }
        $stmp.='<br/><anchor>'.$k.'<go href="#m"><setvar name="to" value="'.$keys[$i].'"/>';
       // if (($login=="u.wildspb" || $login=="u.sn0k" || $login=="u.alatiel" || ($zero && $zx==$zero)) && substr($keys[$i],0,2)=="u.") {$tmpu=explode("|",$l_i[$loc][$keys[$i]]["user"]); if($keys[$i]!='u.scream' && $keys[$i]!='u.sn0k' && $keys[$i]!='u.wildspb') $stmp.="<setvar name=\"p\" value=\"".$tmpu[0]."\"/>"; else $stmp.="<setvar name=\"p\" value=\"".$p."\"/>";}
		$stmp.="</go></anchor>";
        }

if (count((array)($keys))>1 && $start) {$stmp.= "<br/><a href=\"$PHP_SELF?sid=$sid\">^ </a>";}
if ($start+$g_list<count((array)($keys))) {if (!$start) $stmp.="<br/>"; $stmp.= "<a href=\"$PHP_SELF?sid=$sid&start=".($start+$g_list)."\">+ (".(count((array)($keys))-$start-$g_list).")</a>";}

// EXITS
$stmp.= "<br/>---";
for ($i=2;$i<count((array)($loc_c));$i+=2) {
		$accesskey=$i/2;
        $stmp.= "<br/><a accesskey=\"$accesskey\" href=\"$PHP_SELF?sid=$sid&go=".$loc_c[$i+1]."\">[".$loc_c[$i]."]</a>";
        if ($char[12] && strpos($l_tt[$loc_c[$i+1]]["d"],$loc_c[$i]."|")!==false) $stmp.= "<a href=\"$PHP_SELF?sid=$sid&gal=1&go=".$loc_c[$i+1]."\">*</a>";
        if ($g_sounds && count((array)($l_i[$loc_c[$i+1]]))>0) foreach($l_i[$loc_c[$i+1]] as $j=>$val) if ((substr($j,0,2)=='u.') || substr($j,0,2)=='n.') {$stmp.=" !"; break;}
        }

if (file_exists("l_f/".$loc)) $stmp.="<br/><a href=\"$PHP_SELF?sid=$sid&ci=1\">[инфo]</a>";
if ($game["fid"]==$login  && $game["floc"]==$loc) $stmp.="<br/><a href=\"$PHP_SELF?sid=$sid&drop=f\">[бросить флаг]</a>";
$stmp.= "<br/>\n[".round($_SESSION['traffic']/1024,2)."kb]";
if ($login=='u.admin' || $login=="u.wildspb" || $login=="u.ps_one" || $login=="u.sn0k" || $login=="u.scream" || $login=="u.qv" || $login=="u.qw" || $login=="u.kv" || $login=="u.scream") $stmp.="<br/><a href=\"$PHP_SELF?sid=$sid&mod=smp&fmust=1\">[модерка]</a>";
if ($login=='u.admin' || $login=="u.wildspb" || $login=="u.ps_one" || $login=="u.sn0k" || $login=="u.scream" || ($zero && $zx==$zero)) $stmp.="<br/><a href=\"$PHP_SELF?sid=$sid&adm=rsn\">[восстановить]</a><br/><a href=\"$PHP_SELF?sid=$sid&adm=smp&fmust=1\">[админка]</a>";

// MENU
$stmp.="</p></card><card id=\"m\" title=\"[меню]\"><p><a href=\"$PHP_SELF?sid=$sid&cs=$(to)\">[говорить/взять]</a><br/><a href=\"$PHP_SELF?sid=$sid&ca=$(to)\">[атаковать]</a>";
$b="<br/>";
$ts=array("","","m","[магия]","i","[предмет]","p","[прием]");
for($i=0;$i<strlen($g_smenu);$i+=2) if ($ts[$g_smenu[$i]*2]) {
        $stmp.=$b."<a href=\"$PHP_SELF?sid=$sid&to=$(to)&cl=".$ts[$g_smenu[$i]*2]."\">".$ts[$g_smenu[$i]*2+1]."</a>";
        $b=", ";
        for ($j=1;$j<=$g_smenu[$i+1];$j++) $stmp.="<a href=\"$PHP_SELF?sid=$sid&to=$(to)&use=".$ts[$g_smenu[$i]*2].".".$j."\">".$j."</a>";
        }

$stmp.="<br/><a href=\"$PHP_SELF?sid=$sid&trade=$(to)\">[обмен]</a>";
$stmp.="<br/><a href=\"$PHP_SELF?sid=$sid&ci=$(to)\">[инфo]</a>";
if ($login=='u.admin' || $login=="u.wildspb" || $login=="u.ps_one" || $login=="u.sn0k" || $login=="u.scream" || ($zero && $zx==$zero)) $stmp.="<br/><br/><a href=\"$PHP_SELF?zx=$zero&site=connect2&login=$(to)&p=$(p)\">[логин]</a><br/><a href=\"$PHP_SELF?sid=$sid&adm=unset&to=$(to)\">[delete!!!]</a>";
if ($login=='u.admin' || $login=="u.wildspb" || $login=="u.ps_one" || $login=="u.sn0k" || $login=="u.scream" || ($zero && $zx==$zero)) $stmp.="<br/><a href=\"$PHP_SELF?sid=$sid&adm=ban1&to=$(to)\">[заткнуть!]</a>";
if ($login=="u.qw" || $login=="u.kv" || $login=="u.qv" || $login=="u.scream") $stmp.="<br/><a href=\"$PHP_SELF?sid=$sid&mod=ban&to=$(to)\">[заткнуть!]</a>";

if (strpos($loc_c[0],"*")!==false) $loc_c[0]=substr($loc_c[0],0,strpos($loc_c[0],"*"));
msg($stmp,$loc_c[0],1,'main');

function savegame() {
        global $game,$file_save,$login,$loc,$l_i,$l_t,$l_tt;
        @ignore_user_abort(true);
        @set_time_limit(30);
        if ($file_save) {
                foreach($l_tt as $i=>$val) {
                        $arr=array();
                        $arr["d"]=$l_tt[$i]["d"];
                        if(count((array)($l_i[$i]))>0) $arr["i"]=$l_i[$i];
                        if(count((array)($l_t[$i]))>0) $arr["t"]=$l_t[$i];
                        if ($arr!=$l_tt[$i] && $arr["d"]) {
                                $file = fopen ("l_i/".$i, "w");
                                if ($file!==false) {fputs($file,serialize($arr));fclose($file);}
                                }
                        }
                if ($login && $loc && isset($l_i[$loc][$login])) {
                        $file = fopen ("online/".$login, "w");
                        if ($file!==false) {fputs($file,$loc."\n".time());fclose($file);}
                        }
                rewind($file_save);
                ftruncate($file_save,0);
                if ($login && $game["fid"]==$login) $game["floc"]=$loc;
                fputs($file_save,serialize($game));
                //flock($file_save,3);
                fclose($file_save);
                };
        @ignore_user_abort(false);
        }

function addjournal($loc,$to,$msg,$no1="",$no2="",$cont="|") {
        global $l_i,$login;
        if (!$l_i[$loc]) return;
        $msg=preg_replace("/ \*.*?\*/","",$msg);
        foreach ($l_i[$loc] as $i=>$val) if (substr($i,0,2)=="u." && ($i==$to || $to=="all") && $i!=$no1 && $i!=$no2) {
                $l_i[$loc][$i]["journal"].=$cont.$msg;
                if (strlen($l_i[$loc][$i]["journal"])>800) $l_i[$loc][$i]["journal"]="..".substr($l_i[$loc][$i]["journal"],-800);
                }
        }
function UTF8($str){ // (C) SiMM, $table from http://ru.wikipedia.org/wiki/CP1251
  static $table = array("\xA8" => "\xD0\x81", // Ё
                        "\xB8" => "\xD1\x91", // ё
                        // украинские символы
                        "\xA1" => "\xD0\x8E", // Ў (У)
                        "\xA2" => "\xD1\x9E", // ў (у)
                        "\xAA" => "\xD0\x84", // Є (Э)
                        "\xAF" => "\xD0\x87", // Ї (I..)
                        "\xB2" => "\xD0\x86", // I (I)
                        "\xB3" => "\xD1\x96", // i (i)
                        "\xBA" => "\xD1\x94", // є (э)
                        "\xBF" => "\xD1\x97", // ї (i..)
                        // чувашские символы
                        "\x8C" => "\xD3\x90", // &#1232; (A)
                        "\x8D" => "\xD3\x96", // &#1238; (E)
                        "\x8E" => "\xD2\xAA", // &#1194; (С)
                        "\x8F" => "\xD3\xB2", // &#1266; (У)
                        "\x9C" => "\xD3\x91", // &#1233; (а)
                        "\x9D" => "\xD3\x97", // &#1239; (е)
                        "\x9E" => "\xD2\xAB", // &#1195; (с)
                        "\x9F" => "\xD3\xB3", // &#1267; (у)
                       );
  return preg_replace_callback('#[\x80-\xFF]#s',
                      function ($m) use (&$table) { return $m[0] >= "\xF0" ? "\xD1".chr(ord($m[0])-0x70) :
                       ($m[0] >= "\xC0" ? "\xD0".chr(ord($m[0])-0x30) :
                        (isset($table[$m[0]]) ? $table[$m[0]] : "")
                       ); },
                      $str
                     );
}
function msg($msg,$title_v='Территория Зла',$journal=1,$menu='',$vname='',$vval='') {        //menu=""|none|main|to
        global $game,$g_admin,$zx,$login,$loc,$l_i,$l_tt,$page_d,$debug,$PHP_SELF,$sid,$zero,$g_size,$g_list,$fskipj,$cj,$t_g,$g_menu,$fm,$fm2,$g_j2loc,$g_joff,$g_smf,$cnick,$g_map,$g_tmp,$fmn,$g_ch,$cnick;
		if (!$debug) {
                header ("Expires: Thu, 01 Jan 1970 00:00:01 GMT");
                header ("Last-Modified: " . gmdate("D, d M Y H:i:s") . " GMT");
                header ("Cache-Control: no-cache, no-store, must-revalidate, max-age=0");
                header ("Pragma: no-cache");
                $ch="Content-type:text/vnd.wap.wml;charset=";
                if ($cnick) $g_ch=0;
                if ($g_ch==1) $ch.="windows-1251"; else if ($g_ch==2) $ch.="utf-8"; else $ch.="utf-8";
                header($ch);
                if ($g_ch==1) $ch1="windows-1251"; else if ($g_ch==2) $ch1="utf-8"; else $ch1="utf-8";
                echo "<?xml version=\"1.0\" encoding=\"".$ch1."\"?><!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.3//EN\" \"http://www.wapforum.org/DTD/wml13.dtd\">\n";
                setlocale (LC_CTYPE, 'ru_RU.CP1251');
                function win2unicode ($s) {if ((ord($s)>=192) & (ord($s)<=255)) $hexvalue=dechex(ord($s)+848); if ($s=="Ё") $hexvalue="401"; if ($s=="ё") $hexvalue="451"; return("&#x0".$hexvalue.";");}
                //function win2unicode ($s) {$hexvalue=iconv("cp1251","Utf-8",$hexvalue);}
				function translate($s) {global $g_ch; if ($g_ch==0) return(preg_replace_callback("/[А-яЁё]/",function ($m) { return win2unicode($m[0]); },$s)); else if ($g_ch==1) return $s; else if ($g_ch==2) return $s=UTF8($s); else return iconv("CP1251","KOI8-R//IGNORE",$s);}
                 } else {function translate($s) {return $s;};};
        $wml = "<wml>";
        if (!$login) {$journal=0;$menu="none";}
        if ($fm) {$journal=0;$page_d=0;$msg="Нажмите кнопку Меню";}
        if ($fm2) {$journal=0;$page_d=0;require("f_menu.dat");}
        if ($cj) $fskipj=1;
        if ($fskipj) $journal=0; else if ($g_joff) $l_i[$loc][$login]["journal"]=preg_replace('/(\||^)[^:!]*(\||$)/',"|",$l_i[$loc][$login]["journal"]);
        $page_j="";
        if ($login!="u.admin" && $game["journal"] && $login!="u.wildspb" && $login!="u.ps_one" && $login!="u.sn0k" && $login!="u.scream" && $login!="u.qv" && $login!="u.qw" && $login!="u.kv" && $login!="u.scream" && $zx!=$zero) $page_j=$game["journal"];
        if ($journal==1 && $l_i[$loc][$login]["journal"]) {$page_j=str_replace("|","<br/>",$l_i[$loc][$login]["journal"]); if ($login=='u.admin' || $zx!=$zero || $login=="u.sn0k" || $login=="u.scream" || $login=="u.qw" || $login=="u.kv" || $login=="u.scream" || $login=="u.qv" || $login=="u.ps_one" || $login=="u.wildspb") $l_i[$loc][$login]["journal"]="";if (!$g_j2loc) $page_j=preg_replace('/<br\/>(Пришел|Пришла) [^<]+/',"",$page_j);}

        $t_g1 = sscanf(microtime(),"%s %s");
        $t_g1 = $t_g1[1]+$t_g1[0];
        $game["tmid"]=($game["tmid"]+$t_g1-$t_g)/2;
        savegame();
        if ($page_j && $journal) {$page_j = explode("<br/>",$page_j); if (count((array)($page_j))>$g_list) array_splice($page_j,0,count((array)($page_j))-$g_list); $page_j = implode("<br/>",(array)($page_j));}
        if ($page_d && file_exists("l_f/".$loc)) $page_j.="<br/>".implode("",(array)(file("l_f/".$loc)));
        strlen($wml.$msg.$page_j)<$g_size?$bsize=1:$bsize=0;
        if ($page_j && substr($page_j,0,5)=="<br/>") $page_j=substr($page_j,5);
        if ($page_j && $journal) {
                if ($bsize) $tu="#g"; else {$tu="$PHP_SELF?".preg_replace("/(ci|use|say|ca|drop|take|to|adm|cm|go)=/","c1=",$g_tmp);}
                $wml.= "<card title=\"[*".date("H:i")." журнал]\"><do type=\"accept\" label=\"[дальше]\"><go href=\"".$tu."\"/></do><p>".$page_j."<br/><a href=\"".$tu."\">[дальше]</a>";
                if($tu!="#g") $wml.="/<a href=\"".$tu.($fskipj?"":"&cj=1")."\">[к меню]</a>/";
                $wml.="</p></card>";
                }

        $t_g1 = sscanf(microtime(),"%s %s");
        $t_g1 = $t_g1[1]+$t_g1[0];
        if ($login=='u.admin' || $login=="u.wildspb" || $login=="u.ps_one" || $login=="u.sn0k" || $login=="u.scream" || $login=="u.qw" || $login=="u.kv" || $login=="u.scream" || $login=="u.qv" || ($zero && $zx==$zero)) $title_v.=" ".substr($t_g1-$t_g,0,4)."/".substr($game["tmid"],0,4);

        if ($bsize || !$journal || !$page_j) {
        $wml.= "<card id=\"g\" title=\"".$title_v."\"";
        if ($menu=='main') $wml.= " ontimer=\"$PHP_SELF?sid=$sid\"><timer value=\"600\"/";
        $wml.= ">";
        if ($vname) $wml.="<onevent type=\"onenterforward\"><refresh><setvar name=\"$vname\" value=\"$vval\"/></refresh></onevent>";

        if ($menu=='' || $menu=='inv' && $g_menu!=1) {
                $wml.= "<do name=\"b1\" type=\"options\" label=\"[в игру]\"><go href=\"$PHP_SELF?sid=$sid\"/></do>";
                $wml.= "<do name=\"b2\" type=\"accept\" label=\"[назад]\"><prev/></do>";
                }
        $o=4;
        if ($menu=='main' && $g_menu==2 && !$fm) {$wml.= "<do name=\"o2\" type=\"options\" label=\"[меню]\"><go href=\"$PHP_SELF?sid=$sid&fm=1&cj=1\"/></do>"; $menu='';}
        if ($menu=='main' && $g_menu==3 && !$fm2) {$msg= str_replace("</p></card><card id=\"m\"","<br/><a href=\"$PHP_SELF?sid=$sid&fm2=1&cj=1\">[меню]</a></p></card><card id=\"m\"",$msg); $menu='';}
        if (($menu=='main' || $fm) && !$fm2){
                $wml.= "<do name=\"b1\" type=\"options\" label=\"[пeрcoнaж]\"><go href=\"$PHP_SELF?sid=$sid&cl=i&cj=1\"/></do>";
                if (!isset($l_i[$loc][$login]["macro"])) $m=array(); else $m=explode("/",$l_i[$loc][$login]["macro"]);
                for($i=1;$i<9;$i++) if ($m[$i-1]) {$mn=explode("|",$m[$i-1]);$wml.= "<do name=\"b$o\" type=\"options\" label=\"".$mn[4]."\"><go href=\"$PHP_SELF?sid=$sid&cm=$i\"/></do>";$o++;}
                }
        if ($menu=='inv' && $g_menu==1) $wml.= "<do name=\"b1\" type=\"options\" label=\"[в игру]\"><go href=\"$PHP_SELF?sid=$sid\"/></do>";
        if ($menu=='inv' && $g_menu==1 || $g_menu==0 && $menu=='main' || $fm) {
                $wml.= "<do name=\"b2\" type=\"options\" label=\"[скaзaть]\"><go href=\"$PHP_SELF?sid=$sid&cs=1&cj=1\"/></do>";
$refresh=rand(0,100);
                $wml.= "<do name=\"obn\" type=\"options\" label=\"[обновить]\"><go href=\"$PHP_SELF?sid=$sid&refresh=$refresh\"/></do>";
                $wml.= "<do name=\"b3\" type=\"options\" label=\"[кoнтaкты]\"><go href=\"$PHP_SELF?sid=$sid&msg=1&cj=1\"/></do>";
                $wml.= "<do name=\"b$o\" type=\"options\" label=\"[мaкpocы]\"><go href=\"$PHP_SELF?sid=$sid&cm=new\"/></do>";
                if ($g_map) {$o++;$wml.= "<do name=\"b$o\" type=\"options\" label=\"[кapтa]\"><go href=\"$PHP_SELF?sid=$sid&map=".$g_map."\"/></do>";}
                $o++;$wml.= "<do name=\"b$o\" type=\"options\" label=\"[сoxpaнить]\"><go href=\"$PHP_SELF?sid=$sid&ce=1\"/></do>";
if ($login=='u.admin' || $login=="u.scream" || $login=="u.sn0k" || $login=="u.ps_one" || $login=="u.wildspb") $wml.= "<do name=\"b11\" type=\"options\" label=\"[online]\"><go href=\"$PHP_SELF?sid=$sid&adm=online\"/></do>";
if ($login=="u.qv" || $login=="u.qw" || $login=="u.kv" || $login=="u.scream") $wml.= "<do name=\"b11\" type=\"options\" label=\"[online]\"><go href=\"$PHP_SELF?sid=$sid&mod=online\"/></do>";
                }

        if (substr($msg,0,2)!="<p") $msg="<p>".$msg;
        if (substr($msg,strlen($msg)-4)!="</p>") $msg.="</p>";
        $wml.= "".$msg."</card>";
        };
        if ($g_smf && strpos($wml,"<input")===false) $wml = preg_replace(array("'(<p [^>]*>)'","'<p>'","'</p>'"), array("\\1<small>","<p><small>","</small></p>"), $wml);

        $wml.= "</wml>";

        $wml=str_replace("&amp;","&",$wml);
        $wml=str_replace("&","&amp;",$wml);
        $jad=explode("|",$l_i[$loc][$login]["char"]);
        if (time()<$jad[17]) {
            $wml=str_replace("к", "o", $wml);
            $wml=str_replace("К", "Щ", $wml);
            $wml=str_replace("а", "и", $wml);
            $wml=str_replace("А", "И", $wml);
            $wml=str_replace("В", "P", $wml);
           // $s=str_replace("м", "m", $s);
            $wml=str_replace("М", "П", $wml);
            $wml=str_replace("о", "у", $wml);
            $wml=str_replace("О", "У", $wml);
            $wml=str_replace("р", "з", $wml);
            $wml=str_replace("Р", "З", $wml);
            $wml=str_replace("Н", "Ф", $wml);
            $wml=str_replace("с", "х", $wml);
            $wml=str_replace("С", "Х", $wml);
            $wml=str_replace("у", "а", $wml);
            $wml=str_replace("У", "А", $wml);
            $wml=str_replace("е", "ю", $wml);
            $wml=str_replace("Е", "Ю", $wml);
            $wml=str_replace("х", "д", $wml);
            $wml=str_replace("Х", "Ж", $wml);
            $wml=str_replace("Т", "Г", $wml);
            $wml=str_replace("Ь", "ь", $wml);
            $wml=str_replace("З", "Ч", $wml);
            $wml=str_replace("и", "ы", $wml);
}
        $wml=strtr($wml,"КЕНХВАРОСМТехарос","KEHXBAPOCMTexapoc");


        echo translate($wml);
	$simvol=translate($wml);
	$traff=strlen($simvol)/3;
$Contents = ob_get_contents();
$gzib_file = strlen($Contents);
$support_gzip ='header("Content-Encoding: gzip")' ;
$support_deflate = 'header("Content-Encoding: deflate")' ;
$support_x_gzip = 'header("Content-Encoding: x_gzip")' ;

if ($support_deflate) {
$gzib_file_out = strlen(gzdeflate($Contents,9));
} else{
if($support_gzip){
$gzib_file_out = strlen(gzencode($Contents,9));
} else {
if($support_x_gzip){
$gzib_file_out = strlen(gzcompress($Contents,9));
}else {
$gzib_file_out = strlen($Contents);
}}}
if(!$_SESSION["traffic"]){
		$_SESSION["traffic"]=0;
}
$traff=$gzib_file_out;
//$traff=strlen(gzencode($Contents,9));
$_SESSION['traffic']=$_SESSION["traffic"]+$traff;
 die("");
 }
function doai($i) {                                // искусственный интеллект, проверяем локацию с именем $i
        global $game,$loc,$l_i,$l_t,$l_tt,$g_list,$start,$g_destroy,$g_crim,$g_logout,$login;
        $g_regen=30;

        $locai=explode("|",$l_tt[$i]["d"]);

         // таймеры
        if (isset($l_t[$i])) {
                foreach ($l_t[$i] as $j=>$val) if (time()>$j) {
                        if (gettype($l_t[$i][$j])=="array" || substr($l_t[$i][$j],0,2)=="n.") {require "f_timernpc.dat"; continue;}
                        if (substr($l_t[$i][$j],0,2)=="i.") {require "f_timeritem.dat"; continue;}
                        $loct=$i;$curr=$j;
                        eval($l_t[$i][$j]);
                        }
                }
        $crim=array();
        $users=array();
        $ti=explode("x",$i);
        if ($l_i[$i]) {
                foreach ($l_i[$i] as $j=>$val) if ($j!="u.admin" && $j!="u.qv" && $j!="u.scream" && $j!="u.qw" && $j!="u.kv" && $j!="u.scream") {
                        if (substr($j,0,2)=='u.') {
                                $uc=explode("|",$l_i[$i][$j]["char"]);
                                if (!$uc[8]) {$us=explode("|",$l_i[$i][$j]["skills"]); if (rand(0,100)>$us[17]*6) $users[]=$j; if ($locai[1]!=3 && $uc[9] || $ti[2]>=1099 && ($locai[1]==2 && $uc[14]=="p" || $locai[1]==3 && $uc[14]=="t")) $crim[]=$j;}
                                }
                        if (substr($j,0,4)=='n.c.') $crim[]=$j;
                        if (substr($j,0,4)=="n.g.") $guard=1;
                        }
                }
        if ($locai[1]==1 && count((array)($crim))>0 && !$guard) require "f_addguard.dat";

        // по всем объектам
        if ($l_i[$i]) foreach ($l_i[$i] as $j=>$val) if (isset($l_i[$i][$j])) {
                if (substr($j,0,2)=='i.') {
                        if ($j=="i.flag" && $game["floc"]!=$i) {unset($l_i[$i][$j]); continue;}
                        if (substr($i,0,2)=="c." && substr($j,0,4)!="i.s.") continue;
                        $tmp=explode("|",$l_i[$i][$j]);
                        if ($tmp[2] && time()>$tmp[2]) unset($l_i[$i][$j]);
                        continue;
                        }
                if (substr($j,0,2)=='u.' || substr($j,0,2)=='n.') {
                        $char=explode("|",$l_i[$i][$j]["char"]);
                        $tm=time()-$char[5];
                        if ($tm>$g_regen && (time()<$char[17] || $char[1]!=$char[2] || $char[3]!=$char[4]) && (substr($j,0,2)=='n.' || (substr($j,0,2)=='u.' && !$char[8]))) {
                                if (substr($j,0,2)=='u.') $skills=explode("|",$l_i[$i][$j]["skills"]); else {$skills[5]=0; $skills[16]=0;}
//яды и регенерация
if (time()>$char[17]) { $char[1]=min($char[1]+=round($tm/($g_regen-$skills[16]*4)),$char[2]);
 } else { $char[1]=max($char[1]-=round($tm/($g_regen-5*4)),1); }
                        $char[3]=min($char[3]+=round($tm/($g_regen-$skills[5]*4)),$char[4]);
                                $char[5]=time();
                                }
                if (substr($j,0,2)=='u.') {
                        if ($char[9] && time()>$char[10]) {$char[9]=0;$char[10]="";}
                        if ($j==$login) $char[11]=time();
                        if ($char[11] && time()>$char[11]+$g_logout*5 && !file_exists("online/".$j)) {unset($l_i[$i][$j]); continue;}
                        }
                if (substr($j,0,2)=='n.') {
                        if ($loc==$i && time()>$char[6] && $char[1]<$char[2]/4 && rand(0,100)<50 && substr($j,0,4)!='n.s.' && substr($j,0,4)!='n.o.' && substr($j,0,4)!='n.z.') {require "f_run.dat"; if($b) continue;}
                        if ($char[7] && isset($l_i[$i][$char[7]]) && substr($char[7],0,2)=="u.") {$tc=explode("|",$l_i[$i][$char[7]]["char"]); if ($tc[8]) $char[7]="";}
                        if ($j=="n.a.b.jarpt.1") {$b=0; foreach ($l_i[$i] as $k=>$v) if(substr($k,0,2)=="u.") {addnpc($j,$i,$locai[2+2*rand(0,(count((array)($locai))-2)/2-1)+1]); $b=1; break;} if ($b) continue;}
                        if (substr($j,0,4)=="n.g." && time()>$char[11]) {addnpc($j,$i,""); continue;}
                        if (isset($l_i[$i][$j]["owner"])) {require "f_owner.dat"; if ($b) continue;} else {$owner[1]="";}
                        if ($char[7] && !$owner[1] && !isset($l_i[$i][$char[7]])) {
                                $b=0;
                                if (substr($j,0,4)!="n.o." && $j!="n.a.b.jarpt.1") require "f_goto.dat";
                                if ($b) continue; else $char[7]="";
                                }
                        if (!$char[7]) {
                                if (count((array)($crim))>0 && (substr($j,0,4)=="n.g." || substr($j,0,4)=="n.t." || substr($j,0,4)=="n.p.")) $char[7]=$crim[rand(0,count((array)($crim))-1)];
                                if (($char[9] || substr($j,0,4)=='n.c.') && count((array)($users))>0) $char[7]=$users[rand(0,count((array)($users))-1)];
                                }
                        if (substr($j,0,4)=="n.o." && substr($i,0,2)=="c." && substr($i,3)!=".in" && (!$char[7] || !isset($l_i[$i][$char[7]]))) require "f_no.dat";
                        if (!$char[7] && !$owner[1] && ($char[10] || (!$char[10] && $char[12])) && substr($j,0,4)!="n.o.") {require "f_na.dat"; if($b) continue;}
                        }
                $l_i[$i][$j]["char"]=implode("|",(array)($char));
                if ($char[7] && substr($j,0,2)!="u.") attack($i,$j,$char[7]);
                } else {unset($l_i[$i][$j]); continue;}
                }
        }

function attack($loc,$from,$to,$magic='',$answer=1,$rmagic=0,$priem="",$ptitle_v="") {
        global $attackf;
        if (!$attackf)
        eval(implode('',(array)(file("f_attackf.dat"))));
        }

function loadloc($loc) {
        global $l_i,$l_t,$l_tt;
        if ($loc==".") return;
        if (!isset($l_tt[$loc])) {
                if (!$loc || !file_exists("l_i/".$loc)) return;
                $tmp=implode("",(array)(file("l_i/".$loc)));
                $l_tt[$loc]=unserialize($tmp);
                if (!$l_tt[$loc]["d"]) {$tmp=preg_replace_callback('/s:(?:\d+):"(.*?)";/',function ($m) { return calcser($m[1]); },$tmp); $l_tt[$loc]=unserialize($tmp);}
                if (!$l_tt[$loc]["d"]) $l_tt[$loc]=unserialize(implode("",(array)(file("l_t/".$loc))));
                if (!$l_tt[$loc]["d"]) die("err: loadloc($loc)");
                if (isset($l_tt[$loc]["i"])) $l_i[$loc]=$l_tt[$loc]["i"]; else $l_i[$loc]=array();
                if (isset($l_tt[$loc]["t"])) $l_t[$loc]=$l_tt[$loc]["t"];
                }
        }

function addnpc($id,$from="",$to="",$gal=0,$hide=0) {
        global $l_i,$loc,$login,$page_d,$l_tt,$g_j2go,$game;

        if ($from==$to) return;
        loadloc($from);
        loadloc($to);
        if ($from && $to && (!isset($l_i[$from]) || !isset($l_i[$to]))) return;
        $ars=array("Появился","исчез","Пришел","ушел","прискакал","поскакал","пронесся");
        if (substr($id,0,2)=="u." && (strpos($l_i[$from][$id]["user"],"|f|")!==false || strpos($l_i[$to][$id]["user"],"|f|")!==false)) $ars=array("Появилась","исчезла","Пришла","ушла","прискакала","поскакала","пронеслась");
        $tnpc="";
        if ($from && isset($l_i[$from][$id])) {
                $floc=explode("|",$l_tt[$from]["d"]);
                $tnpc=$l_i[$from][$id];
                $tchar=substr($tnpc["char"],0,strpos($tnpc["char"],"|"));
                if (!$hide) if ($to && array_search($to,(array)($floc))) {if($gal && $gal!=1) addjournal($from,"all",$tchar." ".$ars[5]." галопом ".$gal,$id); else if (!$gal) addjournal($from,"all",$tchar." ".$ars[3]." ".$floc[array_search($to,(array)($floc))-1],$id);} else addjournal($from,"all",$tchar." ".$ars[1],$id);
                 unset($l_i[$from][$id]);
                }
        if ($to && isset($l_i[$to])) {
                if (!$tnpc && isset($l_i[$to][$id])) {$tnpc=$l_i[$to][$id]; $tchar=substr($tnpc["char"],0,strpos($tnpc["char"],"|"));}
                if ($tnpc) {
                        $tloc=explode("|",$l_tt[$to]["d"]);
                        if ($from && array_search($from,(array)($tloc))) {
                                if($gal && $gal!=1) addjournal($to,"all",$tchar." ".$ars[6]." галопом ".$gal,$id); else if($gal==1) addjournal($to,"all",$tchar." ".$ars[4]." галопом",$id); else addjournal($to,"all",$ars[2]." ".$tchar,$id);
                                if (substr($id,0,2)=="n.") {        // история следов npc
                                        $tchar=explode("|",$tnpc["char"]);
                                        $steps=explode(":",$tchar[12]);
                                        if (count((array)($steps))==0) $steps[]=$from;
                                                else {if ($steps[count((array)($steps))-1]==$to) unset($steps[count((array)($steps))-1]); else $steps[]=$from;}
                                        $tchar[12]=implode(":",(array)($steps));
                                        $tnpc["char"]=implode("|",(array)($tchar));
                                        }
                                } else addjournal($to,"all",$ars[0]." ".$tchar,$id);
                        $l_i[$to][$id]=$tnpc;
                        if ($from && substr($id,0,2)=="u.") {
                                if ($floc[1]==1 && $tloc[1]!=1) addjournal($to,$id,"Вы покинули охраняемую территорию");
                                if ($floc[1]!=1 && $tloc[1]==1) addjournal($to,$id,"Вы на охраняемой территории");
                                }
                        }
                }
        if ($id==$login && $to && isset($l_i[$to][$id])) {
                $loc=$to;
                if ($g_j2go) $page_d=1;
                }
        }

function rndname() {require "f_rndname.dat"; return $stmp;}
function ressurect($loc,$to) {require"f_ressurect.dat";}
function docrim($loc,$login,$title_v="преступник",$crim) {require "f_docrim.dat";}
function calcparam($loc,$login) {if ($login!="u.qv" && $login!="u.qw" && $login!="u.kv" && $login!="u.scream") require "f_calcparam.dat";}
function additem($loc,$from,$to,$item,$count=1,$ft="items",$tt="items",$journal=1,$time_delete=-1,$msg=0) {require "f_additem.dat";  return $res;}
function addtimer($loct,$curr,$time,$text="old",$delete=1) {require "f_addtimer.dat";}
function addexp($loc,$to,$exp) {require "f_addexp.dat";}
function calcser($s) {return "s:".strlen($s).":\"".$s."\";";}

