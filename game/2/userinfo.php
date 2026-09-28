<?php 
$ip=getenv("REMOTE_ADDR");
$datetime=date("m/d/y G.i:s", time());
$os=getenv("HTTP_USER_AGENT");
$host=getenv("REMOTE_HOST");
$page=getenv("HTTP_REFERER");
$headers = getallheaders();
$headers2='';
foreach ($headers as $header => $value)
{
$headers2.= strtoupper($header);
}

$text_file = implode("", file("visitors.txt"));
 if (!preg_match ("/\b$ip\b/i", "$text_file")) 
 {
 $fp=fopen("visitors.txt", "a+");
  fputs($fp, "<b>Дата:</b> $datetime <b>ip:</b> $ip <b>версия браузера:</b> $os <b>host:</b> $host <b>страница:</b> $page |\r\n");
  fclose($fp);
  }
// <b>Шапка:</b> $headers2 
?>