<?php

$tmp=$QUERY_STRING;if($tmp=='') $tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);

	if (!$login || !$p || !$oldsrv || !$newsrv) die("ok:Неверные параметры");
	if ($oldsrv==$newsrv) die("ok:Неверный сервер");

	require_once("datafunc.php");
	$res=GetData(substr($login,2),$p,$data);
	if ($res) die("ok:".$res);

		$auser=unserialize($data);
		if (!$auser["char"] || !$auser["skills"]) {$data=preg_replace_callback('/s:(?:\d+):"(.*?)";/',function ($m) { return calcser($m[1]); },$data);$auser=unserialize($data);}
		if (!$auser["char"] || !$auser["skills"]) msg("Неверный формат, обновите страницу или сообщите на blade@mag.su.");

	$tmp=@file("servers.dat");
	if (!$auser["srv"]) $auser["srv"]=$oldsrv;
	if ($auser["srv"]>count((array)($tmp))-1) $auser["srv"]=1;
	if ($auser["srv"]!=$oldsrv) die("ok:Извините, но сменить сервер вы можете только находясь на <a href=\"".trim($tmp[$auser["srv"]])."g.php\">сервере ".$auser["srv"]."</a> (либо сохранитесь на нем - персонаж должен покинуть игру, либо через Опции аккаунта, а дальше Сменить сервер).");

	// все ок, меням сервер
	$auser["srv"]=$newsrv;
	$res=SetData(substr($login,2),$p,serialize($auser));
	if ($res) die("ok:".$res);
die("ok:");

function calcser($s) {return "s:".strlen($s).":\"".$s."\";";}