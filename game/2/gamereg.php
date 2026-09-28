<?php   
require("datafunc.php");
//require($_SERVER['DOCUMENT_ROOT']."/javainc.ssp");
require("config.ssp");

$EmptyMess="&#x0418;&#x043C;&#x044F; &#x0438; &#x043F;&#x0430;&#x0440;&#x043E;&#x043B;&#x044C; &#x0434;&#x043E;&#x043B;&#x0436;&#x043D;&#x044B; &#x0431;&#x044B;&#x0442;&#x044C; &#x0432;&#x0432;&#x0435;&#x0434;&#x0435;&#x043D;&#x044B;";
$NNRegMess="&#x0422;&#x0430;&#x043A;&#x043E;&#x0435; &#x0438;&#x043C;&#x044F; &#x0443;&#x0436;&#x0435; &#x0437;&#x0430;&#x0440;&#x0435;&#x0433;&#x0438;&#x0441;&#x0442;&#x0440;&#x0438;&#x0440;&#x043E;&#x0432;&#x0430;&#x043D;&#x043E;";
$InvalidCallMess = "&#x041D;&#x0435;&#x0432;&#x0435;&#x0440;&#x043D;&#x044B;&#x0439; &#x0432;&#x044B;&#x0437;&#x043E;&#x0432; &#x0441;&#x0442;&#x0440;&#x0430;&#x043D;&#x0438;&#x0446;&#x044B;";

$LoginLater = "&#x041F;&#x043E;&#x0432;&#x0442;&#x043E;&#x0440;&#x0438;&#x0442;&#x0435; &#x0447;&#x0435;&#x0440;&#x0435;&#x0437;";

$InvPassMess="&#x041D;&#x0435;&#x0432;&#x0435;&#x0440;&#x043D;&#x044B;&#x0439; &#x043F;&#x0430;&#x0440;&#x043E;&#x043B;&#x044C;.";

$InvNickMess="&#x041D;&#x0435;&#x0432;&#x0435;&#x0440;&#x043D;&#x043E;&#x0435; &#x0438;&#x043C;&#x044F;. &#x0422;&#x0430;&#x043A;&#x043E;&#x0451; &#x0438;&#x043C;&#x044F; &#x043D;&#x0435; &#x0437;&#x0430;&#x0440;&#x0435;&#x0433;&#x0438;&#x0441;&#x0442;&#x0440;&#x0438;&#x0440;&#x043E;&#x0432;&#x0430;&#x043D;&#x043E;.";


function GetRandom() 
{
  mt_srand(time()+(double)microtime()*1000000);
  return mt_rand(1,99999);
} 


$ErrorMess = "Error";

$r = GetRandom();

if (isset($_GET['nn'])) $nn = $_GET['nn'];
if (isset($_GET['pass'])) $pass = $_GET['pass'];
if (isset($_GET['log'])) $log = $_GET['log'];

$nn=ReplaceChar($nn);

if (empty($nn)) 
{
  LogResult($ErrorMess,$EmptyMess,"prev");
}

$ok = openDB(); 
if ($ok != "") echo LogResult($ErrorMess,win2utf($ok),"prev");

if ($log=="old") // зарегистрированный пользователь
{
  $ok = checkpass($nn,$pass,"email",$result);
  if ($ok != "") return LogResult($ErrorMess,win2utf($ok),"prev");
  $email = mysql_result($result,0, "email");
}  
else 
{
  if ($log=="new")
  {
    $sqlSel="select * from users where nick = '$nn'";
    $result=mysql_query($sqlSel) or die(mysql_error()); 
    $Count=mysql_num_rows($result);
    if ($Count != 0)
    {
      LogResult($ErrorMess,$NNRegMess,"prev");
    }
    $email = "";
  }
  else
  {
    LogResult($ErrorMess,$InvalidCallMess,"prev");
  }  
}    
mysql_close();

require("headerwml.ssp");

echo "<card id=\"reg\" title=\"&#x0420;&#x0435;&#x0433;&#x0438;&#x0441;&#x0442;&#x0440;&#x0430;&#x0446;&#x0438;&#x044F;\">"; 

echo "<onevent type=\"onenterforward\">\n";
echo "<refresh>\n";
echo "<setvar name=\"password\" value=\"\"/>\n";
echo "<setvar name=\"email\" value=\"\"/>\n";
echo "</refresh>\n";
echo "</onevent>\n";
 
echo "<p align=\"center\">";            
// Правильный синтаксис ников
echo win2utf("Имя")."<br/>(a-Z,0-9,_)<br/><b>$nn</b><br/>\n";
echo win2utf("Пароль")."<br/>(a-Z,0-9)<br/>";
echo "<input name=\"password\" title=\"=\" type=\"text\" value=\"$pass\" maxlength=\"10\"/>";

echo "E-mail<br/>";
echo "<input name=\"email\" title=\"=\" emptyok=\"true\" type=\"text\" value=\"$email\" maxlength=\"50\"/>";


echo "</p>";
echo "<p>";
echo "<anchor>- ".win2utf("Назад")."<prev/></anchor><br/>\n";
echo "<anchor>- ".win2utf("Сохранить");
echo "<go method=\"post\" href=\"savegamereg.php?pi=$pi&amp;log=$log\">\n";
echo "<postfield name=\"email\" value=\"$(email)\"/>\n";
echo "<postfield name=\"pass\" value=\"$pass\"/>\n"; 
echo "<postfield name=\"nn\" value=\"$nn\"/>\n";
echo "<postfield name=\"newpass\" value=\"$(password)\"/>\n"; 

echo "</go>\n";
echo "</anchor>\n";  
echo "</p>";
echo "<p align=\"center\"><small><a href=\"$homeurl\">$home </a>2004<br/></small></p>\n";
?>
</card> 
</wml>