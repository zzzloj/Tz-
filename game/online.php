<?php 
	$arr=array();
	$dh = opendir('./1/online/'); 
	while (($fname = readdir($dh))!== false) if ($fname!='.' && $fname!='..' && $fname!='1.htaccess' && $fname!='.htaccess') {
		$tmp=unserialize(implode('',file('./1/online/'.$fname)));
		$arr[$fname]=1;
		}
	closedir($dh);
	
	$arr2=array();
	$dh2 = opendir('./2/online/'); 
	while (($fname2 = readdir($dh2))!== false) if ($fname2!='.' && $fname2!='..' && $fname2!='1.htaccess' && $fname2!='.htaccess') {
		$tmp2=unserialize(implode('',file('../2/online/'.$fname2)));
		$arr2[$fname2]=1;
		}
	closedir($dh2);
	if ($a==1) { echo count($arr);  }
elseif ($a==2) { echo count($arr2); }
else		   { echo count($arr)+count($arr2); }
?>