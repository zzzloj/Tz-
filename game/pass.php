<?php 
require("functionChat.ssp");

function ValidNN($s)
{
  return !preg_match("/[^a-z0-9_]/i",$s);
}


$r = GetRandom();
$nn=ReplaceChar($nn);

if (empty($nn))
{
  LogResult(utf(4),utf(26),"index.php?p=$r");
}

if (!ValidNN($nn))
{
  LogResult(utf(4),utf(35),"index.php?p=$r");
}

openDB();
if ($log=="old") // зарегистрированный пользователь
{
  $result = checkpass($nn,$pass,"names,vals");
  InitParam(mysql_result($result,0, "names"),mysql_result($result,0, "vals"));
  $usetrans = GetParam('usetrans');
  //$email = mysql_result($result,0, "email");
  //$profile = mysql_result($result,0, "profile");
  //$subjectlen = mysql_result($result,0, "subjectlen");
  if (!($subjectlen >= 0 and $subjectlen <= 255)) { $subjectlen = $DefSubjectLen;}
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
      LogResult(utf(4),utf(27),"index.php?p=$r&amp;login=$nn&amp;pass=$pass");
    }
    include("antimat.ssp");
    $BadWord = GetBadWord($nn);
    if ($BadWord != "") LogResult(utf(4),utf(25)."<br/>$BadWord", "index.php?p=$r");
    //$email = "";
    $messlim = $DefMessLim;
    $subjectlen = $DefSubjectLen;
    $usetrans = 0;
  }
  else
  {
    LogResult(utf(4),utf(28),"index.php?p=$r");
  }
}
mysql_close();

if ($profile=="1") { require("headerhtml.ssp"); } else { require("headerwml.ssp"); }

if ($profile=="1") { } else { echo "<card id=\"WorldWap\" title=\"WorldWap\">"; }



echo "<onevent type=\"onenterforward\">\n";
echo "<refresh>\n";
echo "<setvar name=\"password\" value=\"\"/>\n";
//echo "<setvar name=\"email\" value=\"\"/>\n";
echo "<setvar name=\"subjectlen\" value=\"\"/>\n";
echo "<setvar name=\"lang\" value=\"\"/>\n";
echo "</refresh>\n";
echo "</onevent>\n";

if ($profile=="1") {	echo "<p align=\"center\">";
echo utf(0).":<b>$nn</b><br/>\n";
echo utf(1).":(a-Z,0-9)<br/>";
echo "<form method=\"post\" action=\"savereg.php?log=$log\">
<input name=\"newpass\" title=\"=\" type=\"text\" value=\"\" maxlength=\"10\"/><br/>";
//echo utf(29)."<br/>";
//echo "<input name=\"email\" title=\"=\" emptyok=\"true\" type=\"text\" value=\"$email\" maxlength=\"50\"/><br/>";

echo utf(30)."<br/>".utf(31)."<br/>(1-255):<br/>";
echo "<input name=\"subjectlen\" format=\"*N\" title=\"=\" type=\"text\" value=\"$subjectlen\" maxlength=\"3\"/><br/>";
echo "<input type=\"hidden\" name=\"refrint\" value=\"$DefRefrInt\">";
echo utf(73).":<br/>";
if ($usetrans == $NOT_SET) $usetrans = 0;
echo "<select name=\"lang\" value=\"$usetrans\" title=\"=\">\n";
echo "<option value=\"0\">Off</option>\n";
echo "<option value=\"1\">On</option>\n";
echo "</select>\n";
echo "<input type=\"hidden\" name=\"pass\" value=\"$pass\">";
echo "<input type=\"hidden\" name=\"nn\" value=\"$nn\">";
echo "<input type=\"hidden\" name=\"newnn\" value=\"$nn\">";
echo "</p><p>";
echo "<input value=\"".utf(32)."\" name=\"do\" type=\"submit\"/>

</form>";


} else {

echo "<p align=\"center\">";
// Правильный синтаксис ников
echo utf(0).":<b>$nn</b><br/>\n";
echo utf(1).":(a-Z,0-9)<br/>";
echo "<input name=\"password\" title=\"=\" type=\"text\" value=\"\" maxlength=\"10\"/><br/>";

//echo utf(29)."<br/>";
//echo "<input name=\"email\" title=\"=\" emptyok=\"true\" type=\"text\" value=\"$email\" maxlength=\"50\"/><br/>";

echo utf(30)."<br/>".utf(31)."<br/>(1-255):<br/>";
echo "<input name=\"subjectlen\" format=\"*N\" title=\"=\" type=\"text\" value=\"$subjectlen\" maxlength=\"3\"/><br/>";

echo utf(73).":<br/>";
if ($usetrans == $NOT_SET) $usetrans = 0;
echo "<select name=\"lang\" value=\"$usetrans\" title=\"=\">\n";
echo "<option value=\"0\">Off</option>\n";
echo "<option value=\"1\">On</option>\n";
echo "</select>\n";

echo "</p>";
echo "<p>";
echo "<a href=\"index.php?p=$r&amp;login=$nn&amp;pass=$pass\">- ".utf(9)."</a><br/>\n";
echo "<anchor>- ".utf(32);
echo "<go method=\"post\" href=\"savereg.php?log=$log\">\n";
//echo "<postfield name=\"email\" value=\"$(email)\"/>\n";
echo "<postfield name=\"subjectlen\" value=\"$(subjectlen)\"/>\n";
echo "<postfield name=\"refrint\" value=\"$DefRefrInt\"/>\n";
echo "<postfield name=\"lang\" value=\"$(lang)\"/>\n";
echo "<postfield name=\"pass\" value=\"$pass\"/>\n";
echo "<postfield name=\"nn\" value=\"$nn\"/>\n";
echo "<postfield name=\"newpass\" value=\"$(password)\"/>\n";
echo "<postfield name=\"newnn\" value=\"$nn\"/>\n";

echo "</go>\n";
echo "</anchor>\n"; }


echo "</p></card></wml>";
//if ($profile=="1") { include("bottom2.inc"); } else { include("bottom.inc"); }
?>
