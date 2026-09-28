<?php 
	$arr=array();
	$dh = opendir('./1/online/'); 
	while (($fname = readdir($dh))!== false) if ($fname!='.' && $fname!='..' && $fname!='1.htaccess' && $fname!='.htaccess') {
		$tmp=unserialize(implode('',(array)(file('./1/online/'.$fname))));
		$arr[$fname]=1;
		}
	closedir($dh);
	
	$arr2=array();
	$dh2 = opendir('./2/online/'); 
	while (($fname2 = readdir($dh2))!== false) if ($fname2!='.' && $fname2!='..' && $fname2!='1.htaccess' && $fname2!='.htaccess') {
		$tmp2=unserialize(implode('',(array)(file('../2/online/'.$fname2))));
		$arr2[$fname2]=1;
		}
	closedir($dh2);
	if (legacy_cmp($a)==1) { echo count((array)($arr));  }
elseif (legacy_cmp($a)==2) { echo count((array)($arr2)); }
else		   { echo count((array)($arr))+count((array)($arr2)); }
?>