<html>
<head>
<tirle_z>MTS Stats</tirle_z>
<meta http-equiv="Content-Type" content="text/html; charset=windows-1251">


<SCRIPT>
function SetToday() {
  with (document) {
form2.from_month.value = <?php echo date ("m", time());?>;
form2.to_day.value = <?php echo date ("d", time());?>;
form2.to_month.value = <?php echo date ("m", time());?>;
form2.year.value = <?php echo date ("Y", time());?>;
form2.from_day.value = <?php echo date ("d", time());?>;
  
  } // with  
}  
</SCRIPT>


</head>

<body onFocus=SetToday() bgcolor="#FFFFFF">
<?=(time()+24*60*60*100);?>
<p>
  MTS Stats<br>
  Current Moscow time: <?php echo date("d/m/Y H:i:s",time());?></p>
<table><tr><td>
<form method="get" action="mts_stat.php" name="form2">
  <table border="1"><tr><td align="center"> 
      AD Stats
  </td></tr><tr><td>
  <p align="center">
	<input type="hidden" name="rep" value="2">
    Year: <input type="text" name="year" size="4" maxlength="4" value="2004">
  </p>  
  <table width="200" border="1">
    <tr> 
      <td>&nbsp;</td>
      <td colspan="2"> 
        <div align="center">Date</div>
      </td>
    </tr>
    <tr> 
      <td height="30">From:</td>
      <td> 
        <select name="from_day" size="1">
          <option value="1">1</option>
          <option value="2">2</option>
          <option value="3">3</option>
          <option value="4">4</option>
          <option value="5">5</option>
          <option value="6">6</option>
          <option value="7">7</option>
          <option value="8">8</option>
          <option value="9">9</option>
          <option value="10">10</option>
          <option value="11">11</option>
          <option value="12">12</option>
          <option value="13">13</option>
          <option value="14">14</option>
          <option value="15">15</option>
          <option value="16">16</option>
          <option value="17">17</option>
          <option value="18">18</option>
          <option value="19">19</option>
          <option value="20">20</option>
          <option value="21">21</option>
          <option value="22">22</option>
          <option value="23">23</option>
          <option value="24">24</option>
          <option value="25">25</option>
          <option value="26">26</option>
          <option value="27">27</option>
          <option value="28">28</option>
          <option value="29">29</option>
          <option value="30">30</option>
          <option value="31">31</option>
        </select>
      </td>
      <td> 
        <select name="from_month" size="1">
          <option value="1">Jan</option>
          <option value="2">Feb</option>
          <option value="3">Mar</option>
          <option value="4">Apr</option>
          <option value="5">May</option>
          <option value="6">Jun</option>
          <option value="7">Jul</option>
          <option value="8">Aug</option>
          <option value="9">Sep</option>
          <option value="10">Oct</option>
          <option value="11">Nov</option>
          <option value="12">Dec</option>
        </select>
      </td>
    </tr>
    <tr> 
      <td height="30">To:</td>
      <td> 
        <select name="to_day" size="1">
          <option value="1">1</option>
          <option value="2">2</option>
          <option value="3">3</option>
          <option value="4">4</option>
          <option value="5">5</option>
          <option value="6">6</option>
          <option value="7">7</option>
          <option value="8">8</option>
          <option value="9">9</option>
          <option value="10">10</option>
          <option value="11">11</option>
          <option value="12">12</option>
          <option value="13">13</option>
          <option value="14">14</option>
          <option value="15">15</option>
          <option value="16">16</option>
          <option value="17">17</option>
          <option value="18">18</option>
          <option value="19">19</option>
          <option value="20">20</option>
          <option value="21">21</option>
          <option value="22">22</option>
          <option value="23">23</option>
          <option value="24">24</option>
          <option value="25">25</option>
          <option value="26">26</option>
          <option value="27">27</option>
          <option value="28">28</option>
          <option value="29">29</option>
          <option value="30">30</option>
          <option value="31">31</option>
        </select>
      </td>
      <td> 
        <select name="to_month" size="1">
          <option value="1">Jan</option>
          <option value="2">Feb</option>
          <option value="3">Mar</option>
          <option value="4">Apr</option>
          <option value="5">May</option>
          <option value="6">Jun</option>
          <option value="7">Jul</option>
          <option value="8">Aug</option>
          <option value="9">Sep</option>
          <option value="10">Oct</option>
          <option value="11">Nov</option>
          <option value="12">Dec</option>
        </select>
      </td>
    </tr>
  </table>
  
  <p align="center">
    <input type="submit" value="Show Stats">
  </p>
  </td></tr></table>
</form>
</td></tr></table>

<?php
$tmp=$QUERY_STRING;if($tmp=='')$tmp=$_SERVER["QUERY_STRING"];
$tmp=urldecode($tmp);
parse_str($tmp, $legacy_qs); extract($legacy_qs);

if ($rep==2) {
	$from=mktime (0,0,0,$from_month,$from_day,$year);
	$to=mktime (0,0,0,$to_month,$to_day,$year);
	if ($to<$from) die("Ошибка: Конечная дата раньше начальной");
	echo "<p><b>MTS Stats</b><br>From: ".date ("d M", $from)."<br/>To &nbsp;&nbsp;&nbsp;: ".date ("d M", $to)."</p>";
	$count=0;

	// по всем датам:
	for($i=$from;$i<=$to;$i+=60*60*24) {
		$fname=date ("Y.m.d", $i);
		$num=0;
		if (@file_exists("1/mts/".$fname)) $num+=@implode("",(array)(file("1/mts/".$fname)));
		if (@file_exists("2/mts/".$fname)) $num+=@implode("",(array)(file("2/mts/".$fname)));
		if (@file_exists("3/mts/".$fname)) $num+=@implode("",(array)(file("3/mts/".$fname)));
		if (@file_exists("4/mts/".$fname)) $num+=@implode("",(array)(file("4/mts/".$fname)));
		echo date ("d/m", $i).": ".$num."<br/>";
		$count+=$num;
		}
	echo "<br/>Всего: ".$count;
	}

?>
</body>
</html>
