<?php 

function ValidNN($s) 
{
  return !preg_match("/[^a-z0-9_]/i",$s);
}

function ValidPass($s) 
{
  return !preg_match("/[^a-z0-9]/i",$s);
}
  
function validEmail($email) 
{
 return  preg_match("/^[a-z0-9]+([-_\.]?[a-z0-9])+@[a-z0-9]+([-_\.]?[a-z0-9])+\.[a-z]{2,4}/i", $email);
}

require("functionChat.ssp");
$r = GetRandom();

$nn=substr(ReplaceChar($nn),0,10);
$newnn = substr(ReplaceChar($newnn),0,10);

if (empty($newpass))
{
  LogResult(utf(4),utf(34),"index.php?p=$r");
}

//require("antimat.ssp");
$BadWord = GetBadWord($newnn);
if ($BadWord != "") LogResult($ErrorMess,"$BadWordMess<br/>$BadWord", "index.php?p=$r");
 
openDB();

$usetrans = $lang;

if ($log=="old")
{
  $result = checkpass($nn,$pass,"names,vals");
  InitParam(mysql_result($result,0, "names"),mysql_result($result,0, "vals"));
  if (strtolower($nn) != strtolower($newnn)) LogResult(utf(4),utf(5),"index.php?p=$r");
}
else 
{ 
  if ($log=="new")
  {
    InitParam("","");
    $sqlSel="select * from users where nick = '$newnn'"; 
    $result=mysql_query($sqlSel) or die(mysql_error()); 
    $Count=mysql_num_rows($result);
    if (legacy_cmp($Count) != 0)
    {
      LogResult(utf(4),utf(27),"index.php?p=$r");
    }
  }  
  else
  { 
    LogResult(utf(4),utf(28),"index.php?p=$r");
  }
}    
  

if (!ValidNN($newnn))
{
  LogResult(utf(4),utf(35),"index.php?p=$r");
}     
       
if (!ValidPass($newpass))
{
  LogResult(utf(4),utf(36),"index.php?p=$r");
}
if (trim($email) != "" and !validEmail($email))
{
  LogResult(utf(4),utf(37),"index.php?p=$r");
}

if (!(legacy_cmp($subjectlen) >= 1 and legacy_cmp($subjectlen) <= 255))
{
  LogResult(utf(4),utf(38),"index.php?p=$r");
}

SetParam('usetrans',$usetrans);

if ($log=="old")
{
  $sqlUpd = "update users set names='$Names', vals='$Values', nick='$newnn', pass='$newpass', where nick = '$nn' and pass='$pass'";
  mysql_query($sqlUpd) or die(mysql_error());
}
else 
{ 
  if ($log=="new")
  {
    $now = msk_time();
    $sqllogin ="insert into users (names,vals,status,sent,regtime,refrint,messlim,mode,nick,pass,email) values ('$Names','$Values','$RegStatus','0', '$now', '$DefRefrInt','$DefMessLim', '$CommonMode','$newnn', '$newpass','$email')"; 
	  mysql_query($sqllogin) or die(mysql_error()); 
  }
}
LogResult(utf(12),utf(39),"index.php?p=$r&amp;login=$nn&amp;pass=$newpass");
mysql_close();   
?>
